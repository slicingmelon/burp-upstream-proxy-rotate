package slicingmelon.burpsocksrotate;

import burp.api.montoya.logging.Logging;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Consumer;

public class SocksProxyService {

    private final Logging logging;
    private final List<ProxyEntry> proxyList;
    private final ReadWriteLock proxyListLock;
    private final Random random = new Random();

    // Configuration
    private int bufferSize;
    private int connectionTimeout;
    private int dataTimeout;
    private boolean verboseLogging;
    private int maxConnections;
    private int maxPooledConnectionsPerProxy;
    private int localPort;

    // Server state
    private ServerSocket serverSocket;
    private Thread serverThread;
    private ExecutorService threadPool;
    private volatile boolean serverRunning = false;

    // Connection pooling
    private final Map<String, Queue<Socket>> proxyConnectionPool = new ConcurrentHashMap<>();
    // Add tracking for failed proxies
    private final Map<String, Integer> proxyFailureCounter = new ConcurrentHashMap<>();
    private final int MAX_FAILURES = 3; // After this many consecutive failures, mark proxy as inactive
    private final int RETRY_ATTEMPTS = 2; // Number of different proxies to try before giving up
    
    // Health check interval (5 minutes)
    private static final long HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000;
    private Thread healthCheckThread;
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);
    
    // Reference to the main extension for UI updates
    private BurpSocksRotate extension;

    public SocksProxyService(List<ProxyEntry> proxyList, ReadWriteLock proxyListLock, Logging logging,
                             int bufferSize, int connectionTimeout, int dataTimeout, boolean verboseLogging,
                             int maxConnections, int maxPooledConnectionsPerProxy) {
        this.proxyList = proxyList;
        this.proxyListLock = proxyListLock;
        this.logging = logging;
        this.bufferSize = bufferSize;
        this.connectionTimeout = connectionTimeout;
        this.dataTimeout = dataTimeout;
        this.verboseLogging = verboseLogging;
        this.maxConnections = maxConnections;
        this.maxPooledConnectionsPerProxy = maxPooledConnectionsPerProxy;
    }

    // Set a reference to the main extension for UI updates
    public void setExtension(BurpSocksRotate extension) {
        this.extension = extension;
    }

    public boolean isRunning() {
        return serverRunning;
    }
    
    public int getLocalPort() {
        return localPort;
    }

    public void start(int port, Runnable onSuccess, Consumer<String> onFailure) {
        if (serverRunning) {
            logInfo("Server is already running.");
            return;
        }

        this.localPort = port;

        // Use a fixed thread pool based on maxConnections
        threadPool = Executors.newFixedThreadPool(maxConnections);

        // Initialize connection pool
        initializeConnectionPool();
        
        // Start health check thread
        startHealthCheckService();

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(localPort);
                serverRunning = true;

                logInfo("SOCKS Proxy Rotator server started on localhost:" + localPort);
                // Invoke success callback on the calling thread's context (likely EDT if called from UI action)
                // Or consider if callbacks should always run via SwingUtilities.invokeLater
                onSuccess.run(); 

                while (serverRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true); // Disable Nagle's algorithm
                        clientSocket.setSoTimeout(dataTimeout);
                        threadPool.execute(() -> handleSocksConnection(clientSocket));
                    } catch (IOException e) {
                        if (serverRunning) {
                            logError("Error accepting connection: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                logError("Error starting server: " + e.getMessage());
                serverRunning = false;
                // Invoke failure callback with the error message
                onFailure.accept(e.getMessage()); 
                // Removed JOptionPane call from here
            } finally {
                 if (serverRunning) { // If it was running but the loop exited (e.g., closed externally)
                     serverRunning = false;
                 }
                 logInfo("Server thread finished.");
            }
        });

        serverThread.start();
    }

    public void stop() {
        if (!serverRunning) {
            logInfo("Server is not running.");
            return;
        }

        serverRunning = false; // Signal the server loop to stop

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close(); // Force close the socket to interrupt accept()
                logInfo("Server socket closed.");
            }
        } catch (IOException e) {
            logError("Error closing server socket: " + e.getMessage());
        }

        if (threadPool != null) {
            threadPool.shutdownNow(); // Attempt to stop all executing tasks
            logInfo("Thread pool shut down.");
        }
        
        // Close all pooled connections
        closeAllPooledConnections();
        logInfo("Closed pooled connections.");

        // Wait for the server thread to finish (optional, with timeout)
        if (serverThread != null && serverThread.isAlive()) {
             try {
                 serverThread.join(1000); // Wait max 1 second
                 if (serverThread.isAlive()) {
                     logError("Server thread did not terminate gracefully.");
                     // Optionally interrupt if still alive: serverThread.interrupt();
                 }
             } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
                 logError("Interrupted while waiting for server thread to stop.");
             }
        }
        
        serverSocket = null;
        threadPool = null;
        serverThread = null;

        // Stop health check thread
        stopHealthCheckService();

        logInfo("SOCKS Proxy Rotator server stopped.");
    }

    private void handleSocksConnection(Socket clientSocket) {
        Socket upstreamSocket = null;
        ProxyEntry proxy = null;
        int retryCount = 0;
        
        try {
            clientSocket.setReceiveBufferSize(bufferSize);
            clientSocket.setSendBufferSize(bufferSize);

            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            // SOCKS5 Greeting
            byte[] buffer = new byte[1024];
            int read = clientIn.read(buffer, 0, 2);
            if (read != 2 || buffer[0] != 0x05) {
                if (verboseLogging) logDebug("Invalid SOCKS protocol version");
                return; // Close handled in finally
            }

            int numMethods = buffer[1] & 0xFF;
            read = clientIn.read(buffer, 0, numMethods);
            if (read != numMethods) {
                if (verboseLogging) logDebug("Failed to read authentication methods");
                return;
            }

            // Send SOCKS5 Response (No Auth)
            clientOut.write(new byte[]{0x05, 0x00});

            // Read Connection Request
            read = clientIn.read(buffer, 0, 4);
            if (read != 4 || buffer[0] != 0x05 || buffer[1] != 0x01) { // CONNECT command
                if (verboseLogging) logDebug("Invalid SOCKS connection request");
                return;
            }

            // Parse Target Address
            int addressType = buffer[3] & 0xFF;
            String targetHost;
            int targetPort;

            switch (addressType) {
                case 0x01: // IPv4
                    byte[] ipv4 = new byte[4];
                    if (clientIn.read(ipv4) != 4) {
                        if (verboseLogging) logDebug("Failed to read IPv4 address"); return;
                    }
                    targetHost = (ipv4[0] & 0xFF) + "." + (ipv4[1] & 0xFF) + "." + (ipv4[2] & 0xFF) + "." + (ipv4[3] & 0xFF);
                    break;
                case 0x03: // Domain name
                    int domainLength = clientIn.read() & 0xFF;
                    byte[] domain = new byte[domainLength];
                     if (clientIn.read(domain) != domainLength) {
                        if (verboseLogging) logDebug("Failed to read domain name"); return;
                    }
                    targetHost = new String(domain);
                    break;
                case 0x04: // IPv6 (Unsupported for now)
                     if (verboseLogging) logDebug("IPv6 addresses not supported yet");
                     // Read and discard IPv6 + port
                     clientIn.read(new byte[16+2]);
                     return;
                default:
                    if (verboseLogging) logDebug("Unsupported address type: " + addressType); return;
            }

            // Read Target Port
            byte[] portBytes = new byte[2];
            if (clientIn.read(portBytes) != 2) {
                if (verboseLogging) logDebug("Failed to read port"); return;
            }
            targetPort = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);

            // RETRY LOOP: Get Upstream Proxy and establish connection with retry
            boolean connectionEstablished = false;
            while (!connectionEstablished && retryCount <= RETRY_ATTEMPTS) {
                // Get a random proxy, excluding previously failed ones in this attempt
                proxy = getRandomProxy(retryCount > 0 ? proxy : null);
                if (proxy == null) {
                    logError("handleSocksConnection: No active proxies available. Closing client connection.");
                    sendSocksErrorResponse(clientOut, (byte)0x01); // General server failure
                    return;
                }

                logInfo("handleSocksConnection: Routing " + targetHost + ":" + targetPort + " via " + proxy.getHost() + ":" + proxy.getPort() + (retryCount > 0 ? " (retry #" + retryCount + ")" : ""));

                // Connect to Upstream Proxy
                try {
                    upstreamSocket = getProxyConnection(proxy); // Throws IOException on failure
                    logInfo("handleSocksConnection: Successfully connected to upstream proxy: " + proxy.getHost() + ":" + proxy.getPort());
                    
                    // Reset failure counter on successful connection
                    String proxyKey = proxy.getHost() + ":" + proxy.getPort();
                    proxyFailureCounter.remove(proxyKey);
                    
                    InputStream upstreamIn = upstreamSocket.getInputStream();
                    OutputStream upstreamOut = upstreamSocket.getOutputStream();

                    // Upstream SOCKS5 Handshake (No Auth)
                    upstreamOut.write(new byte[]{0x05, 0x01, 0x00});
                    read = upstreamIn.read(buffer, 0, 2);
                    if (read != 2 || buffer[0] != 0x05 || buffer[1] != 0x00) {
                        logError("handleSocksConnection: Upstream proxy handshake failed with " + proxy.getHost() + ":" + proxy.getPort() + ". Read=" + read + ", Resp="+(read > 0 ? buffer[0]:"N/A") + ","+(read > 1 ? buffer[1]:"N/A"));
                        // Mark proxy as potentially bad
                        incrementProxyFailure(proxy);
                        closeSocketQuietly(upstreamSocket); // Close upstream before trying next
                        upstreamSocket = null;
                        retryCount++;
                        continue; // Try next proxy
                    }

                    // Forward Connection Request to Upstream
                    upstreamOut.write(new byte[]{0x05, 0x01, 0x00, (byte) addressType}); // CMD=CONNECT, RSV=0
                    if (addressType == 0x01) { // IPv4
                        String[] parts = targetHost.split("\\.");
                        for (String part : parts) upstreamOut.write(Integer.parseInt(part) & 0xFF);
                    } else if (addressType == 0x03) { // Domain
                        upstreamOut.write(targetHost.length() & 0xFF);
                        upstreamOut.write(targetHost.getBytes());
                    }
                    upstreamOut.write((targetPort >> 8) & 0xFF);
                    upstreamOut.write(targetPort & 0xFF);

                    // Read Upstream Response
                    read = upstreamIn.read(buffer, 0, 4); // VER, REP, RSV, ATYP
                    if (read != 4 || buffer[0] != 0x05 || buffer[1] != 0x00) { // Check for success reply (0x00)
                        logError("handleSocksConnection: Upstream proxy refused connection to target " + targetHost + ":" + targetPort + ". Proxy=" + proxy.getHost() + ":" + proxy.getPort() + ", Read=" + read + ", ReplyCode=" + (read > 1 ? buffer[1] : "N/A"));
                        
                        // Not marking proxy as bad here as the issue might be with the target, not the proxy
                        closeSocketQuietly(upstreamSocket);
                        upstreamSocket = null;
                        retryCount++;
                        
                        // If we've tried all proxies or if it's a reply that indicates target issues (not proxy issues)
                        if (retryCount > RETRY_ATTEMPTS || (read > 1 && (buffer[1] == 0x04 || buffer[1] == 0x05 || buffer[1] == 0x06))) {
                            // Send the actual error code to client if we have it
                            if (read > 1) {
                                sendSocksErrorResponse(clientOut, buffer[1]);
                            } else {
                                sendSocksErrorResponse(clientOut, (byte)0x01); // General server failure
                            }
                            return;
                        }
                        
                        continue; // Try next proxy
                    }

                    // Read and discard bind address/port from upstream response
                    int upstreamAtyp = buffer[3] & 0xFF;
                    int bytesToSkip = 0;
                    if (upstreamAtyp == 0x01) bytesToSkip = 4 + 2; // IPv4 + port
                    else if (upstreamAtyp == 0x03) bytesToSkip = (upstreamIn.read() & 0xFF) + 2; // Read len byte + domain + port
                    else if (upstreamAtyp == 0x04) bytesToSkip = 16 + 2; // IPv6 + port
                    
                    if (bytesToSkip > 0) {
                        long skipped = upstreamIn.skip(bytesToSkip);
                        // TODO: check skipped == bytesToSkip if needed
                    }

                    // Send Success Response to Client
                    clientOut.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); // Using IPv4 BND.ADDR placeholder
                    logInfo("handleSocksConnection: Successfully established proxy tunnel for " + targetHost + ":" + targetPort + ". Starting data transfer.");
                    
                    connectionEstablished = true; // Success - exit retry loop
                } catch (IOException e) {
                    logError("handleSocksConnection: Failed to connect to upstream proxy " + proxy.getHost() + ":" + proxy.getPort() + ": " + e.getMessage());
                    
                    // Mark proxy as potentially bad
                    incrementProxyFailure(proxy);
                    
                    // Close any partially established connection
                    closeSocketQuietly(upstreamSocket);
                    upstreamSocket = null;
                    retryCount++;
                    
                    // If we've tried enough proxies, give up
                    if (retryCount > RETRY_ATTEMPTS) {
                        sendSocksErrorResponse(clientOut, (byte)0x01); // General server failure
                        return;
                    }
                }
            }
            
            // If we exit the loop without establishing connection, return
            if (!connectionEstablished || upstreamSocket == null) {
                logError("handleSocksConnection: Failed to establish connection after " + retryCount + " retries");
                sendSocksErrorResponse(clientOut, (byte)0x01); // General server failure
                return;
            }

            // === Data Transfer ===
            AtomicBoolean transferActive = new AtomicBoolean(true);
            Socket finalUpstreamSocket = upstreamSocket; // Final variable for lambda
            InputStream upstreamIn = finalUpstreamSocket.getInputStream();
            OutputStream upstreamOut = finalUpstreamSocket.getOutputStream();
            
            // 1. Client -> Upstream (Background Task)
            threadPool.submit(() -> {
                byte[] buffer1 = new byte[bufferSize];
                try {
                    int bytesRead;
                    while (transferActive.get() && (bytesRead = clientIn.read(buffer1)) != -1) {
                        if (!transferActive.get()) break; // Check again after read
                        upstreamOut.write(buffer1, 0, bytesRead);
                        upstreamOut.flush();
                    }
                } catch (IOException e) {
                    if (transferActive.get()) { // Log only if not intentionally stopped
                        logDebug("handleSocksConnection: Client->Upstream IO Error: " + e.getMessage());
                    }
                } finally {
                    transferActive.set(false); // Signal completion/error
                    // Gently close the write-side of the upstream socket to signal EOF
                    try { if (!finalUpstreamSocket.isClosed()) finalUpstreamSocket.shutdownOutput(); } catch (IOException e) { /* ignore */ }
                    logDebug("handleSocksConnection: Client->Upstream transfer finished.");
                }
            });

            // 2. Upstream -> Client (Current Thread)
            byte[] buffer2 = new byte[bufferSize];
            try {
                int bytesRead;
                while (transferActive.get() && (bytesRead = upstreamIn.read(buffer2)) != -1) {
                    if (!transferActive.get()) break; // Check again after read
                    clientOut.write(buffer2, 0, bytesRead);
                    clientOut.flush();
                }
            } catch (IOException e) {
                if (transferActive.get()) { // Log only if not intentionally stopped
                    logDebug("handleSocksConnection: Upstream->Client IO Error: " + e.getMessage());
                }
            } finally {
                transferActive.set(false); // Signal completion/error
                // Gently close the write-side of the client socket to signal EOF
                try { if (!clientSocket.isClosed()) clientSocket.shutdownOutput(); } catch (IOException e) { /* ignore */ }
                logDebug("handleSocksConnection: Upstream->Client transfer finished.");
            }

            // Wait briefly for the background task to potentially finish signalling
            try { Thread.sleep(50); } catch (InterruptedException ignored) {} 

            // Now that both loops have exited (or errored), try to return the upstream connection
            returnProxyConnection(proxy, upstreamSocket);
            upstreamSocket = null; // Mark as returned/handled

        } catch (IOException e) {
            logError("handleSocksConnection: IO Error during connection setup/transfer for client " + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
            if (proxy != null) {
                incrementProxyFailure(proxy);
            }
            // Close upstream if it wasn't returned/closed yet
            closeSocketQuietly(upstreamSocket);
            try {
                // Try to send error response to client if we haven't sent anything yet
                sendSocksErrorResponse(clientSocket.getOutputStream(), (byte)0x01); // General server failure
            } catch (IOException ignored) {
                // Ignore - we're already handling an error
            }
        } finally {
            closeSocketQuietly(clientSocket); // Always close client socket
            // Ensure upstream is closed if not returned
            if (upstreamSocket != null) {
                closeSocketQuietly(upstreamSocket);
            }
        }
    }

    // Initialize connection pool
    private void initializeConnectionPool() {
        closeAllPooledConnections(); // Clear existing connections first
        proxyListLock.readLock().lock();
        try {
            for (ProxyEntry proxy : proxyList) {
                if (proxy.isActive()) {
                    proxyConnectionPool.put(
                        proxy.getHost() + ":" + proxy.getPort(),
                        new ConcurrentLinkedQueue<>()
                    );
                }
            }
            logInfo("Connection pool initialized for active proxies.");
        } finally {
            proxyListLock.readLock().unlock();
        }
    }

    // Get a connection from the pool or create a new one
    private Socket getProxyConnection(ProxyEntry proxy) throws IOException {
        String key = proxy.getHost() + ":" + proxy.getPort();
        Queue<Socket> pool = proxyConnectionPool.get(key);

        if (pool != null) {
            Socket socket = null;
            
            // Try to get a valid socket from the pool
            while ((socket = pool.poll()) != null) {
                if (socket.isConnected() && !socket.isClosed()) {
                    try {
                        socket.setSoTimeout(100); // Quick check timeout
                        
                        // Try to validate socket is still good
                        if (socket.getInputStream().available() >= 0 && !socket.isInputShutdown() && !socket.isOutputShutdown()) {
                            socket.setSoTimeout(dataTimeout); // Restore original timeout
                            if (verboseLogging) logDebug("Reusing pooled connection to " + key);
                            return socket;
                        }
                    } catch (IOException e) {
                        // Socket is bad, close it and try next one
                        if (verboseLogging) logDebug("Pooled connection to " + key + " seems dead: " + e.getMessage());
                    }
                }
                
                // If we reached here, socket is not usable
                closeSocketQuietly(socket);
            }
        }

        // Create a new connection if pool is empty or no valid connections found
        if (verboseLogging) logDebug("Creating new connection to " + key);
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(bufferSize);
        socket.setSendBufferSize(bufferSize);
        
        try {
            socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), connectionTimeout);
            // Successfully connected, now set the data timeout
            socket.setSoTimeout(dataTimeout);
        } catch (IOException e) {
            logError("Failed to connect to upstream proxy " + key + ": " + e.getMessage());
            closeSocketQuietly(socket); // Ensure socket is closed on connection failure
            throw e; // Re-throw exception
        }

        return socket;
    }

    // Return a connection to the pool
    private void returnProxyConnection(ProxyEntry proxy, Socket socket) {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
             if (verboseLogging) logDebug("Attempted to return invalid socket to pool for " + proxy.getHost() + ":" + proxy.getPort());
            return;
        }

        String key = proxy.getHost() + ":" + proxy.getPort();
        Queue<Socket> pool = proxyConnectionPool.get(key);

        // Check if the pool exists, if proxy is still active, and if pool has space
        if (pool != null && proxy.isActive() && pool.size() < maxPooledConnectionsPerProxy) {
             try {
                 // Clear any potential remaining data in input buffer before pooling
                 InputStream is = socket.getInputStream();
                 
                 // Test if socket is still usable before returning to pool
                 socket.setSoTimeout(100); // Quick timeout for health check
                 
                 // Check if socket is still valid by checking if there's data or if socket is still connected
                 if (is.available() > 0) {
                     // Data left in the stream - not a clean connection to pool
                     if (verboseLogging) logDebug("Socket has remaining data, closing instead of pooling");
                     closeSocketQuietly(socket);
                     return;
                 }
                 
                 // Reset timeout to original value
                 socket.setSoTimeout(dataTimeout);
                 
                 // Add to pool
                 pool.offer(socket);
                 if (verboseLogging) logDebug("Returned connection to pool: " + key + " (Pool size: " + pool.size() + ")");
            } catch (IOException e) {
                 logError("Error preparing socket for pooling (" + key + "): " + e.getMessage());
                 closeSocketQuietly(socket); // Close if error occurs during prep
            }
        } else {
             if (verboseLogging) {
                 String reason = (pool == null) ? "pool doesn't exist" : 
                                (!proxy.isActive()) ? "proxy is inactive" : 
                                "pool full";
                 logDebug("Closing connection instead of pooling (" + key + "): " + reason);
             }
            closeSocketQuietly(socket); // Close if pool doesn't exist or is full
        }
    }

    // Close all pooled connections
    private void closeAllPooledConnections() {
        logInfo("Closing all pooled connections...");
        int closedCount = 0;
        for (Queue<Socket> pool : proxyConnectionPool.values()) {
            Socket socket;
            while ((socket = pool.poll()) != null) {
                closeSocketQuietly(socket);
                closedCount++;
            }
        }
        proxyConnectionPool.clear(); // Ensure the map itself is cleared
        logInfo("Closed " + closedCount + " pooled connections.");
    }

    // Send a SOCKS5 error response to the client
    private void sendSocksErrorResponse(OutputStream out, byte errorCode) {
        try {
            // SOCKS5 error response
            out.write(new byte[]{0x05, errorCode, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
        } catch (IOException e) {
            logDebug("Failed to send error response to client: " + e.getMessage());
        }
    }

    // Track proxy failures and mark as inactive if too many failures
    private void incrementProxyFailure(ProxyEntry proxy) {
        if (proxy == null) return;
        
        String proxyKey = proxy.getHost() + ":" + proxy.getPort();
        int failures = proxyFailureCounter.getOrDefault(proxyKey, 0) + 1;
        proxyFailureCounter.put(proxyKey, failures);
        
        if (failures >= MAX_FAILURES) {
            logError("Marking proxy " + proxyKey + " as inactive after " + failures + " consecutive failures");
            
            String errorMessage = "Marked inactive after " + failures + " consecutive failures";
            
            // Update proxy entry in the list
            proxy.setActive(false);
            proxy.setErrorMessage(errorMessage);
            
            // Notify extension for UI update
            if (extension != null) {
                extension.notifyProxyFailure(proxy.getHost(), proxy.getPort(), errorMessage);
            }
            
            proxyFailureCounter.remove(proxyKey); // Reset counter
            
            // Remove from connection pool
            Queue<Socket> pool = proxyConnectionPool.remove(proxyKey);
            if (pool != null) {
                Socket socket;
                while ((socket = pool.poll()) != null) {
                    closeSocketQuietly(socket);
                }
            }
        }
    }

    // Get a random proxy, optionally excluding a specific proxy
    private ProxyEntry getRandomProxy(ProxyEntry excludeProxy) {
        proxyListLock.readLock().lock();
        try {
            if (proxyList.isEmpty()) {
                return null;
            }

            List<ProxyEntry> activeProxies = new ArrayList<>();
            for (ProxyEntry proxy : proxyList) {
                if (proxy.isActive() && (excludeProxy == null || 
                    !proxy.getHost().equals(excludeProxy.getHost()) || 
                    proxy.getPort() != excludeProxy.getPort())) {
                    activeProxies.add(proxy);
                }
            }

            if (activeProxies.isEmpty()) {
                return null; // No active proxies
            }

            return activeProxies.get(random.nextInt(activeProxies.size()));
        } finally {
            proxyListLock.readLock().unlock();
        }
    }
    
    // Keep the existing method for backward compatibility
    private ProxyEntry getRandomProxy() {
        return getRandomProxy(null);
    }

    // Helper method to close socket without throwing checked exceptions
    private void closeSocketQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore in quiet close
                 //logError("Error closing socket quietly: " + e.getMessage()); // Optional logging
            }
        }
    }

    // Logging helpers
    private void logInfo(String message) {
        logging.logToOutput("[INFO] " + message);
    }

    private void logError(String message) {
        logging.logToError("[ERROR] " + message);
    }

    private void logDebug(String message) {
        if (verboseLogging) {
            logging.logToOutput("[DEBUG] " + message);
        }
    }

    // Start health check thread to periodically test proxies
    private void startHealthCheckService() {
        if (healthCheckThread != null && healthCheckThread.isAlive()) {
            return; // Already running
        }
        
        healthCheckRunning.set(true);
        healthCheckThread = new Thread(() -> {
            logInfo("Health check service started");
            
            while (healthCheckRunning.get() && serverRunning) {
                try {
                    // Sleep first to allow initial connections to stabilize
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                    
                    if (!healthCheckRunning.get() || !serverRunning) {
                        break;
                    }
                    
                    logInfo("Running periodic proxy health check");
                    performProxyHealthCheck();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logError("Error during health check: " + e.getMessage());
                }
            }
            
            logInfo("Health check service stopped");
        });
        
        healthCheckThread.setDaemon(true);
        healthCheckThread.start();
    }
    
    // Stop health check thread
    private void stopHealthCheckService() {
        healthCheckRunning.set(false);
        if (healthCheckThread != null) {
            healthCheckThread.interrupt();
            try {
                healthCheckThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            healthCheckThread = null;
        }
    }
    
    // Perform health check on all proxies
    private void performProxyHealthCheck() {
        List<ProxyEntry> proxiesToCheck;
        proxyListLock.readLock().lock();
        try {
            proxiesToCheck = new ArrayList<>(proxyList);
        } finally {
            proxyListLock.readLock().unlock();
        }
        
        for (ProxyEntry proxy : proxiesToCheck) {
            if (!serverRunning || !healthCheckRunning.get()) {
                break; // Stop if service is shutting down
            }
            
            // Test proxy
            String proxyKey = proxy.getHost() + ":" + proxy.getPort();
            logDebug("Health check: Testing proxy " + proxyKey);
            
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), connectionTimeout);
                
                // Try a simple SOCKS5 handshake
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                
                // SOCKS5 greeting with no auth
                out.write(new byte[]{0x05, 0x01, 0x00});
                out.flush();
                
                // Read SOCKS5 response
                byte[] response = new byte[2];
                int bytesRead = in.read(response);
                
                if (bytesRead == 2 && response[0] == 0x05 && response[1] == 0x00) {
                    logDebug("Health check: Proxy " + proxyKey + " is healthy");
                    
                    // If previously marked as problematic, reset its status
                    if (proxyFailureCounter.containsKey(proxyKey)) {
                        proxyFailureCounter.remove(proxyKey);
                    }
                    
                    // If it was inactive but now works, reactivate it
                    if (!proxy.isActive()) {
                        proxy.setActive(true);
                        proxy.setErrorMessage("");
                        logInfo("Health check: Reactivated previously inactive proxy " + proxyKey);
                        
                        // Notify extension
                        if (extension != null) {
                            extension.notifyProxyReactivated(proxy.getHost(), proxy.getPort());
                        }
                    }
                } else {
                    logError("Health check: Proxy " + proxyKey + " failed handshake test");
                    incrementProxyFailure(proxy);
                }
            } catch (IOException e) {
                logError("Health check: Proxy " + proxyKey + " failed connectivity test: " + e.getMessage());
                incrementProxyFailure(proxy);
            } finally {
                closeSocketQuietly(socket);
            }
        }
    }
} 