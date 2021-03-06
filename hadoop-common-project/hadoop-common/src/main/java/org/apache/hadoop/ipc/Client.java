/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ipc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryPolicy.RetryAction;
import org.apache.hadoop.ipc.RPC.RpcKind;
import org.apache.hadoop.ipc.Server.AuthProtocol;
import org.apache.hadoop.ipc.protobuf.IpcConnectionContextProtos.IpcConnectionContextProto;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos.RpcRequestHeaderProto;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos.RpcRequestHeaderProto.OperationProto;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos.RpcResponseHeaderProto;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos.RpcResponseHeaderProto.RpcErrorCodeProto;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos.RpcResponseHeaderProto.RpcStatusProto;
import org.apache.hadoop.net.ConnectTimeoutException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.KerberosInfo;
import org.apache.hadoop.security.SaslRpcClient;
import org.apache.hadoop.security.SaslRpcServer.AuthMethod;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ProtoUtil;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.concurrent.AsyncGet;
import org.apache.htrace.core.Span;
import org.apache.htrace.core.Tracer;

import javax.net.SocketFactory;
import javax.security.sasl.Sasl;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.hadoop.ipc.RpcConstants.PING_CALL_ID;

/** A client for an IPC service.  IPC calls take a single {@link Writable} as a
 * parameter, and return a {@link Writable} as their value.  A service runs on
 * a port and is defined by a parameter class and a value class.
 *
 * 请求服务的一方称为客户方或用户方，
 * 凡是对外提出服务请求都需有 Client 对象经手
 * Client 对象只是在 Server 面前代表客户方，而绝非客户方的全部
 * 
 * @see Server
 */
@Public
@InterfaceStability.Evolving
public class Client implements AutoCloseable {
  
  public static final Log LOG = LogFactory.getLog(Client.class);

  /** A counter for generating call IDs. */
  private static final AtomicInteger callIdCounter = new AtomicInteger();

  private static final ThreadLocal<Integer> callId = new ThreadLocal<Integer>();
  private static final ThreadLocal<Integer> retryCount = new ThreadLocal<Integer>();
  private static final ThreadLocal<Object> EXTERNAL_CALL_HANDLER
      = new ThreadLocal<>();
  private static final ThreadLocal<AsyncGet<? extends Writable, IOException>>
      ASYNC_RPC_RESPONSE = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> asynchronousMode =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  @SuppressWarnings("unchecked")
  @Unstable
  public static <T extends Writable> AsyncGet<T, IOException>
      getAsyncRpcResponse() {
    return (AsyncGet<T, IOException>) ASYNC_RPC_RESPONSE.get();
  }

  /** Set call id and retry count for the next call. */
  public static void setCallIdAndRetryCount(int cid, int rc,
                                            Object externalHandler) {
    Preconditions.checkArgument(cid != RpcConstants.INVALID_CALL_ID);
    Preconditions.checkState(callId.get() == null);
    Preconditions.checkArgument(rc != RpcConstants.INVALID_RETRY_COUNT);

    callId.set(cid);
    retryCount.set(rc);
    EXTERNAL_CALL_HANDLER.set(externalHandler);
  }

  // Connection 线程集合
  // 一个 Client 可以有多个对外连接，每个连接都有个 Connection 线程照看
  private ConcurrentMap<ConnectionId, Connection> connections =
      new ConcurrentHashMap<>();

  private Class<? extends Writable> valueClass;   // class of call values
  private AtomicBoolean running = new AtomicBoolean(true); // if client runs
  final private Configuration conf;

  private SocketFactory socketFactory;           // how to create sockets
  private int refCount = 1;

  private final int connectionTimeout;

  private final boolean fallbackAllowed;
  private final byte[] clientId;
  private final int maxAsyncCalls;
  private final AtomicInteger asyncCallCounter = new AtomicInteger(0);
  
  final static int CONNECTION_CONTEXT_CALL_ID = -3;

  /**
   * Executor on which IPC calls' parameters are sent.
   * Deferring the sending of parameters to a separate
   * thread isolates them from thread interruptions in the
   * calling code.
   */
  private final ExecutorService sendParamsExecutor;
  private final static ClientExecutorServiceFactory clientExcecutorFactory =
      new ClientExecutorServiceFactory();

  private static class ClientExecutorServiceFactory {
    private int executorRefCount = 0;
    private ExecutorService clientExecutor = null;
    
    /**
     * Get Executor on which IPC calls' parameters are sent.
     * If the internal reference counter is zero, this method
     * creates the instance of Executor. If not, this method
     * just returns the reference of clientExecutor.
     * 
     * @return An ExecutorService instance
     */
    synchronized ExecutorService refAndGetInstance() {
      if (executorRefCount == 0) {
        clientExecutor = Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("IPC Parameter Sending Thread #%d")
            .build());
      }
      executorRefCount++;
      
      return clientExecutor;
    }
    
    /**
     * Cleanup Executor on which IPC calls' parameters are sent.
     * If reference counter is zero, this method discards the
     * instance of the Executor. If not, this method
     * just decrements the internal reference counter.
     * 
     * @return An ExecutorService instance if it exists.
     *   Null is returned if not.
     */
    synchronized ExecutorService unrefAndCleanup() {
      executorRefCount--;
      assert(executorRefCount >= 0);
      
      if (executorRefCount == 0) {
        clientExecutor.shutdown();
        try {
          if (!clientExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
            clientExecutor.shutdownNow();
          }
        } catch (InterruptedException e) {
          LOG.warn("Interrupted while waiting for clientExecutor" +
              " to stop");
          clientExecutor.shutdownNow();
          Thread.currentThread().interrupt();
        }
        clientExecutor = null;
      }
      
      return clientExecutor;
    }
  };
  
  /**
   * set the ping interval value in configuration
   * 
   * @param conf Configuration
   * @param pingInterval the ping interval
   */
  public static final void setPingInterval(Configuration conf,
      int pingInterval) {
    conf.setInt(CommonConfigurationKeys.IPC_PING_INTERVAL_KEY, pingInterval);
  }

  /**
   * Get the ping interval from configuration;
   * If not set in the configuration, return the default value.
   * 
   * @param conf Configuration
   * @return the ping interval
   */
  public static final int getPingInterval(Configuration conf) {
    return conf.getInt(CommonConfigurationKeys.IPC_PING_INTERVAL_KEY,
        CommonConfigurationKeys.IPC_PING_INTERVAL_DEFAULT);
  }

  /**
   * The time after which a RPC will timeout.
   * If ping is not enabled (via ipc.client.ping), then the timeout value is the 
   * same as the pingInterval.
   * If ping is enabled, then there is no timeout value.
   * 
   * @param conf Configuration
   * @return the timeout period in milliseconds. -1 if no timeout value is set
   * @deprecated use {@link #getRpcTimeout(Configuration)} instead
   */
  @Deprecated
  final public static int getTimeout(Configuration conf) {
    int timeout = getRpcTimeout(conf);
    if (timeout > 0)  {
      return timeout;
    }
    if (!conf.getBoolean(CommonConfigurationKeys.IPC_CLIENT_PING_KEY,
        CommonConfigurationKeys.IPC_CLIENT_PING_DEFAULT)) {
      return getPingInterval(conf);
    }
    return -1;
  }

  /**
   * The time after which a RPC will timeout.
   *
   * @param conf Configuration
   * @return the timeout period in milliseconds.
   */
  public static final int getRpcTimeout(Configuration conf) {
    int timeout =
        conf.getInt(CommonConfigurationKeys.IPC_CLIENT_RPC_TIMEOUT_KEY,
            CommonConfigurationKeys.IPC_CLIENT_RPC_TIMEOUT_DEFAULT);
    return (timeout < 0) ? 0 : timeout;
  }
  /**
   * set the connection timeout value in configuration
   * 
   * @param conf Configuration
   * @param timeout the socket connect timeout value
   */
  public static final void setConnectTimeout(Configuration conf, int timeout) {
    conf.setInt(CommonConfigurationKeys.IPC_CLIENT_CONNECT_TIMEOUT_KEY, timeout);
  }

  @VisibleForTesting
  public static final ExecutorService getClientExecutor() {
    return Client.clientExcecutorFactory.clientExecutor;
  }
  /**
   * Increment this client's reference count
   *
   */
  synchronized void incCount() {
    refCount++;
  }
  
  /**
   * Decrement this client's reference count
   *
   */
  synchronized void decCount() {
    refCount--;
  }
  
  /**
   * Return if this client has no reference
   * 
   * @return true if this client has no reference; false otherwise
   */
  synchronized boolean isZeroReference() {
    return refCount==0;
  }

  /** Check the rpc response header. */
  void checkResponse(RpcResponseHeaderProto header) throws IOException {
    if (header == null) {
      throw new EOFException("Response is null.");
    }
    if (header.hasClientId()) {
      // check client IDs
      final byte[] id = header.getClientId().toByteArray();
      if (!Arrays.equals(id, RpcConstants.DUMMY_CLIENT_ID)) {
        if (!Arrays.equals(id, clientId)) {
          throw new IOException("Client IDs not matched: local ID="
              + StringUtils.byteToHexString(clientId) + ", ID in response="
              + StringUtils.byteToHexString(header.getClientId().toByteArray()));
        }
      }
    }
  }

  Call createCall(RPC.RpcKind rpcKind, Writable rpcRequest) {
    return new Call(rpcKind, rpcRequest);
  }

  /** 
   * Class that represents an RPC call
   * 每个 Call 对象代表着一次 RPC 调用
   */
  static class Call {
    final int id;               // call id
    final int retry;           // retry count
    final Writable rpcRequest;  // the serialized rpc request
    Writable rpcResponse;       // null if rpc has error
    IOException error;          // exception, null if success
    final RPC.RpcKind rpcKind;      // Rpc EngineKind
    boolean done;               // true when call is done
    private final Object externalHandler;

    private Call(RPC.RpcKind rpcKind, Writable param) {
      this.rpcKind = rpcKind;
      this.rpcRequest = param;

      final Integer id = callId.get();
      if (id == null) {
        this.id = nextCallId();
      } else {
        callId.set(null);
        this.id = id;
      }
      
      final Integer rc = retryCount.get();
      if (rc == null) {
        this.retry = 0;
      } else {
        this.retry = rc;
      }

      this.externalHandler = EXTERNAL_CALL_HANDLER.get();
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + id;
    }

    /** Indicate when the call is complete and the
     * value or error are available.  Notifies by default.  */
    protected synchronized void callComplete() {
      this.done = true;
      notify();                                 // notify caller

      if (externalHandler != null) {
        synchronized (externalHandler) {
          externalHandler.notify();
        }
      }
    }

    /** Set the exception when there is an error.
     * Notify the caller the call is done.
     * 
     * @param error exception thrown by the call; either local or remote
     */
    public synchronized void setException(IOException error) {
      this.error = error;
      callComplete();
    }
    
    /** Set the return value when there is no error. 
     * Notify the caller the call is done.
     * 
     * @param rpcResponse return value of the rpc call.
     */
    public synchronized void setRpcResponse(Writable rpcResponse) {
      this.rpcResponse = rpcResponse;
      callComplete();
    }
    
    public synchronized Writable getRpcResponse() {
      return rpcResponse;
    }
  }

  /** Thread that reads responses and notifies callers.  Each connection owns a
   * socket connected to a remote address.  Calls are multiplexed through this
   * socket: responses may be delivered out of order. */
  private class Connection extends Thread {  // 在 Client 每个连接都有一个 Connection 线程
    private InetSocketAddress server;             // server ip:port
    private final ConnectionId remoteId;                // connection id
    private AuthMethod authMethod; // authentication method
    private AuthProtocol authProtocol;
    private int serviceClass;
    private SaslRpcClient saslRpcClient;
    
    private Socket socket = null;                 // connected socket
    private IpcStreams ipcStreams;
    private final int maxResponseLength;
    private final int rpcTimeout;
    private int maxIdleTime; //connections will be culled if it was idle for 
    //maxIdleTime msecs
    private final RetryPolicy connectionRetryPolicy;
    private final int maxRetriesOnSasl;
    private int maxRetriesOnSocketTimeouts;
    private final boolean tcpNoDelay; // if T then disable Nagle's Algorithm
    private final boolean tcpLowLatency; // if T then use low-delay QoS
    private final boolean doPing; //do we need to send ping message
    private final int pingInterval; // how often sends ping to the server
    private final int soTimeout; // used by ipc ping and rpc timeout
    private byte[] pingRequest; // ping message

    // currently active calls
    private Hashtable<Integer, Call> calls = new Hashtable<Integer, Call>();
    private AtomicLong lastActivity = new AtomicLong();// last I/O activity time
    private AtomicBoolean shouldCloseConnection = new AtomicBoolean();  // indicate if the connection is closed
    private IOException closeException; // close reason
    
    private final Object sendRpcRequestLock = new Object();

    /**
     * 构造函数
     * @param remoteId
     * @param serviceClass
     * @throws IOException
     */
    public Connection(ConnectionId remoteId, int serviceClass) throws IOException {
      this.remoteId = remoteId;
      this.server = remoteId.getAddress();
      if (server.isUnresolved()) {
        throw NetUtils.wrapException(server.getHostName(),
            server.getPort(),
            null,
            0,
            new UnknownHostException());
      }
      this.maxResponseLength = remoteId.conf.getInt(
          CommonConfigurationKeys.IPC_MAXIMUM_RESPONSE_LENGTH,
          CommonConfigurationKeys.IPC_MAXIMUM_RESPONSE_LENGTH_DEFAULT);
      this.rpcTimeout = remoteId.getRpcTimeout();
      this.maxIdleTime = remoteId.getMaxIdleTime();
      this.connectionRetryPolicy = remoteId.connectionRetryPolicy;
      this.maxRetriesOnSasl = remoteId.getMaxRetriesOnSasl();  // 采用 Sasl 加密时允许失败的次数
      this.maxRetriesOnSocketTimeouts = remoteId.getMaxRetriesOnSocketTimeouts();
      this.tcpNoDelay = remoteId.getTcpNoDelay();
      this.tcpLowLatency = remoteId.getTcpLowLatency();
      this.doPing = remoteId.getDoPing();  // 需不需要定时发送 Ping 报文
      if (doPing) {
        // 如果需要就预先准备好一个用于 Ping 的报头备用
        // construct a RPC header with the callId as the ping callId
        ResponseBuffer buf = new ResponseBuffer();  // 分配缓冲区，以供构筑 Ping 报文
        RpcRequestHeaderProto pingHeader = ProtoUtil
            .makeRpcRequestHeader(RpcKind.RPC_PROTOCOL_BUFFER,
                OperationProto.RPC_FINAL_PACKET, PING_CALL_ID,
                RpcConstants.INVALID_RETRY_COUNT, clientId);
        pingHeader.writeDelimitedTo(buf);
        pingRequest = buf.toByteArray();
      }
      this.pingInterval = remoteId.getPingInterval();  // Ping 的时间间隔
      if (rpcTimeout > 0) {
        // effective rpc timeout is rounded up to multiple of pingInterval
        // if pingInterval < rpcTimeout.
        this.soTimeout = (doPing && pingInterval < rpcTimeout) ?
            pingInterval : rpcTimeout;
      } else {
        this.soTimeout = pingInterval;
      }
      this.serviceClass = serviceClass;
      if (LOG.isDebugEnabled()) {
        LOG.debug("The ping interval is " + this.pingInterval + " ms.");
      }

      UserGroupInformation ticket = remoteId.getTicket();  // 对方所要求的门票
      // try SASL if security is enabled or if the ugi contains tokens.
      // this causes a SIMPLE client with tokens to attempt SASL
      boolean trySasl = UserGroupInformation.isSecurityEnabled() ||
                        (ticket != null && !ticket.getTokens().isEmpty());  // 表示需不需要 试试 SASL 加密
      this.authProtocol = trySasl ? AuthProtocol.SASL : AuthProtocol.NONE;  // 如果需要就是 AuthProtocol.SASL
      
      this.setName("IPC Client (" + socketFactory.hashCode() +") connection to " +
          server.toString() +
          " from " + ((ticket==null)?"an unknown user":ticket.getUserName()));
      this.setDaemon(true);  // 将线程设置成 Daemon，关闭标准输入输出管道
    }

    /** Update lastActivity with the current time. */
    private void touch() {
      lastActivity.set(Time.now());
    }

    /**
     * Add a call to this connection's call queue and notify
     * a listener; synchronized.
     * Returns false if called during shutdown.
     * @param call to add
     * @return true if the call was added.
     */
    private synchronized boolean addCall(Call call) {
      if (shouldCloseConnection.get())
        return false;
      calls.put(call.id, call);
      notify();
      return true;
    }

    /** This class sends a ping to the remote side when timeout on
     * reading. If no failure is detected, it retries until at least
     * a byte is read.
     */
    private class PingInputStream extends FilterInputStream {
      /* constructor */
      protected PingInputStream(InputStream in) {
        super(in);
      }

      /* Process timeout exception
       * if the connection is not going to be closed or 
       * the RPC is not timed out yet, send a ping.
       */
      private void handleTimeout(SocketTimeoutException e, int waiting)
          throws IOException {
        if (shouldCloseConnection.get() || !running.get() ||
            (0 < rpcTimeout && rpcTimeout <= waiting)) {
          throw e;
        } else {
          sendPing();
        }
      }
      
      /** Read a byte from the stream.
       * Send a ping if timeout on read. Retries if no failure is detected
       * until a byte is read.
       * @throws IOException for any IO problem other than socket timeout
       */
      @Override
      public int read() throws IOException {
        int waiting = 0;
        do {
          try {
            return super.read();
          } catch (SocketTimeoutException e) {
            waiting += soTimeout;
            handleTimeout(e, waiting);
          }
        } while (true);
      }

      /** Read bytes into a buffer starting from offset <code>off</code>
       * Send a ping if timeout on read. Retries if no failure is detected
       * until a byte is read.
       * 
       * @return the total number of bytes read; -1 if the connection is closed.
       */
      @Override
      public int read(byte[] buf, int off, int len) throws IOException {
        int waiting = 0;
        do {
          try {
            return super.read(buf, off, len);
          } catch (SocketTimeoutException e) {
            waiting += soTimeout;
            handleTimeout(e, waiting);
          }
        } while (true);
      }
    }
    
    private synchronized void disposeSasl() {
      if (saslRpcClient != null) {
        try {
          saslRpcClient.dispose();
          saslRpcClient = null;
        } catch (IOException ignored) {
        }
      }
    }
    
    private synchronized boolean shouldAuthenticateOverKrb() throws IOException {
      UserGroupInformation loginUser = UserGroupInformation.getLoginUser();
      UserGroupInformation currentUser = UserGroupInformation.getCurrentUser();
      UserGroupInformation realUser = currentUser.getRealUser();
      if (authMethod == AuthMethod.KERBEROS && loginUser != null &&
      // Make sure user logged in using Kerberos either keytab or TGT
          loginUser.hasKerberosCredentials() &&
          // relogin only in case it is the login user (e.g. JT)
          // or superuser (like oozie).
          (loginUser.equals(currentUser) || loginUser.equals(realUser))) {
        return true;
      }
      return false;
    }

    private synchronized AuthMethod setupSaslConnection(IpcStreams streams)
        throws IOException {
      // Do not use Client.conf here! We must use ConnectionId.conf, since the
      // Client object is cached and shared between all RPC clients, even those
      // for separate services.
      saslRpcClient = new SaslRpcClient(remoteId.getTicket(),
          remoteId.getProtocol(), remoteId.getAddress(), remoteId.conf);
      return saslRpcClient.saslConnect(streams);
    }

    /**
     * Update the server address if the address corresponding to the host
     * name has changed.
     *
     * @return true if an addr change was detected.
     * @throws IOException when the hostname cannot be resolved.
     */
    private synchronized boolean updateAddress() throws IOException {
      // Do a fresh lookup with the old host name.
      InetSocketAddress currentAddr = NetUtils.createSocketAddrForHost(
                               server.getHostName(), server.getPort());

      if (!server.equals(currentAddr)) {
        LOG.warn("Address change detected. Old: " + server.toString() +
                                 " New: " + currentAddr.toString());
        server = currentAddr;
        return true;
      }
      return false;
    }
    
    private synchronized void setupConnection(
        UserGroupInformation ticket) throws IOException {
      short ioFailures = 0;
      short timeoutFailures = 0;
      while (true) {
        try {
          this.socket = socketFactory.createSocket();
          this.socket.setTcpNoDelay(tcpNoDelay);
          this.socket.setKeepAlive(true);
          
          if (tcpLowLatency) {
            /*
             * This allows intermediate switches to shape IPC traffic
             * differently from Shuffle/HDFS DataStreamer traffic.
             *
             * IPTOS_RELIABILITY (0x04) | IPTOS_LOWDELAY (0x10)
             *
             * Prefer to optimize connect() speed & response latency over net
             * throughput.
             */
            this.socket.setTrafficClass(0x04 | 0x10);
            this.socket.setPerformancePreferences(1, 2, 0);
          }

          /*
           * Bind the socket to the host specified in the principal name of the
           * client, to ensure Server matching address of the client connection
           * to host name in principal passed.
           */
          InetSocketAddress bindAddr = null;
          if (ticket != null && ticket.hasKerberosCredentials()) {
            KerberosInfo krbInfo = 
              remoteId.getProtocol().getAnnotation(KerberosInfo.class);
            if (krbInfo != null) {
              String principal = ticket.getUserName();
              String host = SecurityUtil.getHostFromPrincipal(principal);
              // If host name is a valid local address then bind socket to it
              InetAddress localAddr = NetUtils.getLocalInetAddress(host);
              if (localAddr != null) {
                if (LOG.isDebugEnabled()) {
                  LOG.debug("Binding " + principal + " to " + localAddr);
                }
                bindAddr = new InetSocketAddress(localAddr, 0);
              }
            }
          }

          NetUtils.connect(this.socket, server, bindAddr, connectionTimeout);  // 与 Server 建立网络连接
          this.socket.setSoTimeout(soTimeout);
          return;
        } catch (ConnectTimeoutException toe) {
          /* Check for an address change and update the local reference.
           * Reset the failure counter if the address was changed
           */
          if (updateAddress()) {
            timeoutFailures = ioFailures = 0;
          }
          handleConnectionTimeout(timeoutFailures++,
              maxRetriesOnSocketTimeouts, toe);
        } catch (IOException ie) {
          if (updateAddress()) {
            timeoutFailures = ioFailures = 0;
          }
          handleConnectionFailure(ioFailures++, ie);
        }
      } // 如果在此过程中发生异常而失败，就循环重试
    }

    /**
     * If multiple clients with the same principal try to connect to the same
     * server at the same time, the server assumes a replay attack is in
     * progress. This is a feature of kerberos. In order to work around this,
     * what is done is that the client backs off randomly and tries to initiate
     * the connection again. The other problem is to do with ticket expiry. To
     * handle that, a relogin is attempted.
     */
    private synchronized void handleSaslConnectionFailure(
        final int currRetries, final int maxRetries, final Exception ex,
        final Random rand, final UserGroupInformation ugi) throws IOException,
        InterruptedException {
      ugi.doAs(new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws IOException, InterruptedException {
          final short MAX_BACKOFF = 5000;
          closeConnection();
          disposeSasl();
          if (shouldAuthenticateOverKrb()) {
            if (currRetries < maxRetries) {
              if(LOG.isDebugEnabled()) {
                LOG.debug("Exception encountered while connecting to "
                    + "the server : " + ex);
              }
              // try re-login
              if (UserGroupInformation.isLoginKeytabBased()) {
                UserGroupInformation.getLoginUser().reloginFromKeytab();
              } else if (UserGroupInformation.isLoginTicketBased()) {
                UserGroupInformation.getLoginUser().reloginFromTicketCache();
              }
              // have granularity of milliseconds
              //we are sleeping with the Connection lock held but since this
              //connection instance is being used for connecting to the server
              //in question, it is okay
              Thread.sleep((rand.nextInt(MAX_BACKOFF) + 1));
              return null;
            } else {
              String msg = "Couldn't setup connection for "
                  + UserGroupInformation.getLoginUser().getUserName() + " to "
                  + remoteId;
              LOG.warn(msg, ex);
              throw (IOException) new IOException(msg).initCause(ex);
            }
          } else {
            LOG.warn("Exception encountered while connecting to "
                + "the server : " + ex);
          }
          if (ex instanceof RemoteException)
            throw (RemoteException) ex;
          throw new IOException(ex);
        }
      });
    }

    
    /** Connect to the server and set up the I/O streams. It then sends
     * a header to the server and starts
     * the connection thread that waits for responses.
     *
     * 这个 setupIOstreams 其实就是专为新创 Connection 而设的，因为凡是原已存在的 Connection 都已有 socket，所以一进来就返回了，
     * 而新建的 Connection 线程，则通过 setupConnection() 创建 socket并与 Server 建立网络连接，之后建立带缓冲的数据输入输出流
     */
    private synchronized void setupIOstreams(
        AtomicBoolean fallbackToSimpleAuth) {
      if (socket != null || shouldCloseConnection.get()) {  // 与 Server 的连接已建立
        return;
      }
      UserGroupInformation ticket = remoteId.getTicket();
      if (ticket != null) {
        final UserGroupInformation realUser = ticket.getRealUser();
        if (realUser != null) {
          ticket = realUser;
        }
      }
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Connecting to "+server);
        }
        Span span = Tracer.getCurrentSpan();
        if (span != null) {
          span.addTimelineAnnotation("IPC client connecting to " + server);
        }
        short numRetries = 0;
        Random rand = null;
        while (true) {  // 新创建的 Connection 需要建立与 Server 方的连接
          setupConnection(ticket);  // **
          ipcStreams = new IpcStreams(socket, maxResponseLength);  // ** 建立带缓冲的输入输出流
          writeConnectionHeader(ipcStreams);  // 写这个流，就是通过 socket 发送报头
          if (authProtocol == AuthProtocol.SASL) {
            try {
              authMethod = ticket
                  .doAs(new PrivilegedExceptionAction<AuthMethod>() {
                    @Override
                    public AuthMethod run()
                        throws IOException, InterruptedException {
                      return setupSaslConnection(ipcStreams);
                    }
                  });
            } catch (IOException ex) {
              if (saslRpcClient == null) {
                // whatever happened -it can't be handled, so rethrow
                throw ex;
              }
              // otherwise, assume a connection problem
              authMethod = saslRpcClient.getAuthMethod();
              if (rand == null) {
                rand = new Random();
              }
              handleSaslConnectionFailure(numRetries++, maxRetriesOnSasl, ex,
                  rand, ticket);
              continue;
            }
            if (authMethod != AuthMethod.SIMPLE) {
              // Sasl connect is successful. Let's set up Sasl i/o streams.
              ipcStreams.setSaslClient(saslRpcClient);
              // for testing
              remoteId.saslQop =
                  (String)saslRpcClient.getNegotiatedProperty(Sasl.QOP);
              LOG.debug("Negotiated QOP is :" + remoteId.saslQop);
              if (fallbackToSimpleAuth != null) {
                fallbackToSimpleAuth.set(false);
              }
            } else if (UserGroupInformation.isSecurityEnabled()) {
              if (!fallbackAllowed) {
                throw new IOException("Server asks us to fall back to SIMPLE " +
                    "auth, but this client is configured to only allow secure " +
                    "connections.");
              }
              if (fallbackToSimpleAuth != null) {
                fallbackToSimpleAuth.set(true);
              }
            }
          }

          if (doPing) {  // 在输入流上增加对 Ping 的处理
            ipcStreams.setInputStream(new PingInputStream(ipcStreams.in));
          }

          writeConnectionContext(remoteId, authMethod); // **

          // update last activity time
          touch();

          span = Tracer.getCurrentSpan();
          if (span != null) {
            span.addTimelineAnnotation("IPC client connected to " + server);
          }

          // start the receiver thread after the socket connection has been set
          // up
          start();  // 启动这个 connection 线程
          return;
        }
      } catch (Throwable t) {
        if (t instanceof IOException) {
          markClosed((IOException)t);
        } else {
          markClosed(new IOException("Couldn't set up IO streams: " + t, t));
        }
        close();
      }
    }
    
    private void closeConnection() {
      if (socket == null) {
        return;
      }
      // close the current connection
      try {
        socket.close();
      } catch (IOException e) {
        LOG.warn("Not able to close a socket", e);
      }
      // set socket to null so that the next call to setupIOstreams
      // can start the process of connect all over again.
      socket = null;
    }

    /* Handle connection failures due to timeout on connect
     *
     * If the current number of retries is equal to the max number of retries,
     * stop retrying and throw the exception; Otherwise backoff 1 second and
     * try connecting again.
     *
     * This Method is only called from inside setupIOstreams(), which is
     * synchronized. Hence the sleep is synchronized; the locks will be retained.
     *
     * @param curRetries current number of retries
     * @param maxRetries max number of retries allowed
     * @param ioe failure reason
     * @throws IOException if max number of retries is reached
     */
    private void handleConnectionTimeout(
        int curRetries, int maxRetries, IOException ioe) throws IOException {

      closeConnection();

      // throw the exception if the maximum number of retries is reached
      if (curRetries >= maxRetries) {
        throw ioe;
      }
      LOG.info("Retrying connect to server: " + server + ". Already tried "
          + curRetries + " time(s); maxRetries=" + maxRetries);
    }

    private void handleConnectionFailure(int curRetries, IOException ioe
        ) throws IOException {
      closeConnection();

      final RetryAction action;
      try {
        action = connectionRetryPolicy.shouldRetry(ioe, curRetries, 0, true);
      } catch(Exception e) {
        throw e instanceof IOException? (IOException)e: new IOException(e);
      }
      if (action.action == RetryAction.RetryDecision.FAIL) {
        if (action.reason != null) {
          LOG.warn("Failed to connect to server: " + server + ": "
              + action.reason, ioe);
        }
        throw ioe;
      }

      // Throw the exception if the thread is interrupted
      if (Thread.currentThread().isInterrupted()) {
        LOG.warn("Interrupted while trying for connection");
        throw ioe;
      }

      try {
        Thread.sleep(action.delayMillis);
      } catch (InterruptedException e) {
        throw (IOException)new InterruptedIOException("Interrupted: action="
            + action + ", retry policy=" + connectionRetryPolicy).initCause(e);
      }
      LOG.info("Retrying connect to server: " + server + ". Already tried "
          + curRetries + " time(s); retry policy is " + connectionRetryPolicy);
    }

    /**
     * Write the connection header - this is sent when connection is established
     * +----------------------------------+
     * |  "hrpc" 4 bytes                  |      
     * +----------------------------------+
     * |  Version (1 byte)                |
     * +----------------------------------+
     * |  Service Class (1 byte)          |
     * +----------------------------------+
     * |  AuthProtocol (1 byte)           |      
     * +----------------------------------+
     */
    private void writeConnectionHeader(IpcStreams streams)
        throws IOException {
      // Write out the header, version and authentication method.
      // The output stream is buffered but we must not flush it yet.  The
      // connection setup protocol requires the client to send multiple
      // messages before reading a response.
      //
      //   insecure: send header+context+call, read
      //   secure  : send header+negotiate, read, (sasl), context+call, read
      //
      // The client must flush only when it's prepared to read.  Otherwise
      // "broken pipe" exceptions occur if the server closes the connection
      // before all messages are sent.
      final DataOutputStream out = streams.out;
      synchronized (out) {
        out.write(RpcConstants.HEADER.array());  // 4 个字节："hrpc"
        out.write(RpcConstants.CURRENT_VERSION);  // 1个字节的版本号
        out.write(serviceClass);  // 一个字节的服务类别
        out.write(authProtocol.callId);  // 一个字节的身份认证规程，NONE(0)或 SASL(-33)
      }
    }

    /* Write the connection context header for each connection
     * Out is not synchronized because only the first thread does this.
     */
    private void writeConnectionContext(ConnectionId remoteId,
                                        AuthMethod authMethod)
                                            throws IOException {
      // Write out the ConnectionHeader
      IpcConnectionContextProto message = ProtoUtil.makeIpcConnectionContext(
          RPC.getProtocolName(remoteId.getProtocol()),
          remoteId.getTicket(),
          authMethod);
      RpcRequestHeaderProto connectionContextHeader = ProtoUtil
          .makeRpcRequestHeader(RpcKind.RPC_PROTOCOL_BUFFER,
              OperationProto.RPC_FINAL_PACKET, CONNECTION_CONTEXT_CALL_ID,
              RpcConstants.INVALID_RETRY_COUNT, clientId);
      // do not flush.  the context and first ipc call request must be sent
      // together to avoid possibility of broken pipes upon authz failure.
      // see writeConnectionHeader
      final ResponseBuffer buf = new ResponseBuffer();
      connectionContextHeader.writeDelimitedTo(buf);
      message.writeDelimitedTo(buf);  // 创建请求报文
      synchronized (ipcStreams.out) {
        ipcStreams.sendRequest(buf.toByteArray());
      }
    }

    /* wait till someone signals us to start reading RPC response or
     * it is idle too long, it is marked as to be closed, 
     * or the client is marked as not running.
     * 
     * Return true if it is time to read a response; false otherwise.
     */
    private synchronized boolean waitForWork() {
      if (calls.isEmpty() && !shouldCloseConnection.get()  && running.get())  {
        long timeout = maxIdleTime-
              (Time.now()-lastActivity.get());
        if (timeout>0) {
          try {
            wait(timeout);
          } catch (InterruptedException e) {}
        }
      }
      
      if (!calls.isEmpty() && !shouldCloseConnection.get() && running.get()) {
        return true;
      } else if (shouldCloseConnection.get()) {
        return false;
      } else if (calls.isEmpty()) { // idle connection closed or stopped
        markClosed(null);
        return false;
      } else { // get stopped but there are still pending requests 
        markClosed((IOException)new IOException().initCause(
            new InterruptedException()));
        return false;
      }
    }

    public InetSocketAddress getRemoteAddress() {
      return server;
    }

    /* Send a ping to the server if the time elapsed 
     * since last I/O activity is equal to or greater than the ping interval
     */
    private synchronized void sendPing() throws IOException {
      long curTime = Time.now();
      if ( curTime - lastActivity.get() >= pingInterval) {
        lastActivity.set(curTime);
        synchronized (ipcStreams.out) {
          ipcStreams.sendRequest(pingRequest);
          ipcStreams.flush();
        }
      }
    }

    @Override
    public void run() {
      if (LOG.isDebugEnabled())
        LOG.debug(getName() + ": starting, having connections " 
            + connections.size());

      try {
        while (waitForWork()) {//wait here for work - read or close connection  Connection 线程的主循环
          receiveRpcResponse();  // ** 接收来自对方的响应
        }
      } catch (Throwable t) {
        // This truly is unexpected, since we catch IOException in receiveResponse
        // -- this is only to be really sure that we don't leave a client hanging
        // forever.
        LOG.warn("Unexpected error reading responses on connection " + this, t);
        markClosed(new IOException("Error reading responses", t));
      }
      
      close();
      
      if (LOG.isDebugEnabled())
        LOG.debug(getName() + ": stopped, remaining connections "
            + connections.size());
    }

    /** Initiates a rpc call by sending the rpc request to the remote server.
     * Note: this is not called from the Connection thread, but by other
     * threads.
     * @param call - the rpc request
     */
    public void sendRpcRequest(final Call call)
        throws InterruptedException, IOException {
      if (shouldCloseConnection.get()) {
        return;
      }

      // Serialize the call to be sent. This is done from the actual
      // caller thread, rather than the sendParamsExecutor thread,
      
      // so that if the serialization throws an error, it is reported
      // properly. This also parallelizes the serialization.
      //
      // Format of a call on the wire:
      // 0) Length of rest below (1 + 2)
      // 1) RpcRequestHeader  - is serialized Delimited hence contains length
      // 2) RpcRequest
      //
      // Items '1' and '2' are prepared here. 
      RpcRequestHeaderProto header = ProtoUtil.makeRpcRequestHeader(
          call.rpcKind, OperationProto.RPC_FINAL_PACKET, call.id, call.retry,
          clientId);

      final ResponseBuffer buf = new ResponseBuffer();  // 准备好一个缓冲区
      header.writeDelimitedTo(buf);  // 将 RPC 请求报头写入缓冲区
      RpcWritable.wrap(call.rpcRequest).writeTo(buf);  // 将 RPC 请求内容写入缓冲区

      synchronized (sendRpcRequestLock) {
        // 提交给 JVM 要求安排线程(Runnable 对象)执行其 run() 函数
        Future<?> senderFuture = sendParamsExecutor.submit(new Runnable() {  // 创建一个 Runnable 对象（作为线程运行）
          @Override
          public void run() {  // 这是 Runnable 的 run 函数
            try {
              synchronized (ipcStreams.out) {
                if (shouldCloseConnection.get()) {
                  return;
                }
                if (LOG.isDebugEnabled()) {
                  LOG.debug(getName() + " sending #" + call.id
                      + " " + call.rpcRequest);
                }
                // RpcRequestHeader + RpcRequest
                ipcStreams.sendRequest(buf.toByteArray());  // 把缓冲区的数据写出去
                ipcStreams.flush();  // 发送
              }
            } catch (IOException e) {
              // exception at this point would leave the connection in an
              // unrecoverable state (eg half a call left on the wire).
              // So, close the connection, killing any outstanding calls
              markClosed(e);
            } finally {
              //the buffer is just an in-memory buffer, but it is still polite to
              // close early
              IOUtils.closeStream(buf);
            }
          }
        });
      
        try {
          senderFuture.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          
          // cause should only be a RuntimeException as the Runnable above
          // catches IOException
          if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
          } else {
            throw new RuntimeException("unexpected checked exception", cause);
          }
        }
      }
    }

    /* Receive a response.
     * Because only one receiver, so no synchronization on in.
     */
    private void receiveRpcResponse() {
      if (shouldCloseConnection.get()) {
        return;
      }
      touch();
      
      try {
        ByteBuffer bb = ipcStreams.readResponse();
        RpcWritable.Buffer packet = RpcWritable.Buffer.wrap(bb);
        RpcResponseHeaderProto header =
            packet.getValue(RpcResponseHeaderProto.getDefaultInstance());
        checkResponse(header);

        int callId = header.getCallId();
        if (LOG.isDebugEnabled())
          LOG.debug(getName() + " got value #" + callId);

        RpcStatusProto status = header.getStatus();
        if (status == RpcStatusProto.SUCCESS) {
          Writable value = packet.newInstance(valueClass, conf);
          final Call call = calls.remove(callId);
          call.setRpcResponse(value);
        }
        // verify that packet length was correct
        if (packet.remaining() > 0) {
          throw new RpcClientException("RPC response length mismatch");
        }
        if (status != RpcStatusProto.SUCCESS) { // Rpc Request failed
          final String exceptionClassName = header.hasExceptionClassName() ?
                header.getExceptionClassName() : 
                  "ServerDidNotSetExceptionClassName";
          final String errorMsg = header.hasErrorMsg() ? 
                header.getErrorMsg() : "ServerDidNotSetErrorMsg" ;
          final RpcErrorCodeProto erCode = 
                    (header.hasErrorDetail() ? header.getErrorDetail() : null);
          if (erCode == null) {
             LOG.warn("Detailed error code not set by server on rpc error");
          }
          RemoteException re = new RemoteException(exceptionClassName, errorMsg, erCode);
          if (status == RpcStatusProto.ERROR) {
            final Call call = calls.remove(callId);
            call.setException(re);
          } else if (status == RpcStatusProto.FATAL) {
            // Close the connection
            markClosed(re);
          }
        }
      } catch (IOException e) {
        markClosed(e);
      }
    }
    
    private synchronized void markClosed(IOException e) {
      if (shouldCloseConnection.compareAndSet(false, true)) {
        closeException = e;
        notifyAll();
      }
    }
    
    /** Close the connection. */
    private synchronized void close() {
      if (!shouldCloseConnection.get()) {
        LOG.error("The connection is not in the closed state");
        return;
      }

      // We have marked this connection as closed. Other thread could have
      // already known it and replace this closedConnection with a new one.
      // We should only remove this closedConnection.
      connections.remove(remoteId, this);

      // close the streams and therefore the socket
      IOUtils.closeStream(ipcStreams);
      disposeSasl();

      // clean up all calls
      if (closeException == null) {
        if (!calls.isEmpty()) {
          LOG.warn(
              "A connection is closed for no cause and calls are not empty");

          // clean up calls anyway
          closeException = new IOException("Unexpected closed connection");
          cleanupCalls();
        }
      } else {
        // log the info
        if (LOG.isDebugEnabled()) {
          LOG.debug("closing ipc connection to " + server + ": " +
              closeException.getMessage(),closeException);
        }

        // cleanup calls
        cleanupCalls();
      }
      closeConnection();
      if (LOG.isDebugEnabled())
        LOG.debug(getName() + ": closed");
    }
    
    /* Cleanup all calls and mark them as done */
    private void cleanupCalls() {
      Iterator<Entry<Integer, Call>> itor = calls.entrySet().iterator() ;
      while (itor.hasNext()) {
        Call c = itor.next().getValue(); 
        itor.remove();
        c.setException(closeException); // local exception
      }
    }
  }

  /** Construct an IPC client whose values are of the given {@link Writable}
   * class. */
  public Client(Class<? extends Writable> valueClass, Configuration conf, 
      SocketFactory factory) {
    this.valueClass = valueClass;
    this.conf = conf;
    this.socketFactory = factory;
    this.connectionTimeout = conf.getInt(CommonConfigurationKeys.IPC_CLIENT_CONNECT_TIMEOUT_KEY,
        CommonConfigurationKeys.IPC_CLIENT_CONNECT_TIMEOUT_DEFAULT);
    this.fallbackAllowed = conf.getBoolean(CommonConfigurationKeys.IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_KEY,
        CommonConfigurationKeys.IPC_CLIENT_FALLBACK_TO_SIMPLE_AUTH_ALLOWED_DEFAULT);
    this.clientId = ClientId.getClientId();
    this.sendParamsExecutor = clientExcecutorFactory.refAndGetInstance();
    this.maxAsyncCalls = conf.getInt(
        CommonConfigurationKeys.IPC_CLIENT_ASYNC_CALLS_MAX_KEY,
        CommonConfigurationKeys.IPC_CLIENT_ASYNC_CALLS_MAX_DEFAULT);
  }

  /**
   * Construct an IPC client with the default SocketFactory
   * @param valueClass
   * @param conf
   */
  public Client(Class<? extends Writable> valueClass, Configuration conf) {
    this(valueClass, conf, NetUtils.getDefaultSocketFactory(conf));
  }
 
  /** Return the socket factory of this client
   *
   * @return this client's socket factory
   */
  SocketFactory getSocketFactory() {
    return socketFactory;
  }

  /** Stop all threads related to this client.  No further calls may be made
   * using this client. */
  public void stop() {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Stopping client");
    }

    if (!running.compareAndSet(true, false)) {
      return;
    }
    
    // wake up all connections
    for (Connection conn : connections.values()) {
      conn.interrupt();
    }
    
    // wait until all connections are closed
    while (!connections.isEmpty()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
      }
    }
    
    clientExcecutorFactory.unrefAndCleanup();
  }

  /** 
   * Make a call, passing <code>rpcRequest</code>, to the IPC server defined by
   * <code>remoteId</code>, returning the rpc respond.
   *
   * @param rpcKind
   * @param rpcRequest -  contains serialized method and method parameters
   * @param remoteId - the target rpc server
   * @param fallbackToSimpleAuth - set to true or false during this method to
   *   indicate if a secure client falls back to simple auth
   * @returns the rpc response
   * Throws exceptions if there are network problems or if the remote code
   * threw an exception.
   */
  public Writable call(RPC.RpcKind rpcKind, Writable rpcRequest,
      ConnectionId remoteId, AtomicBoolean fallbackToSimpleAuth)
      throws IOException {
    return call(rpcKind, rpcRequest, remoteId, RPC.RPC_SERVICE_CLASS_DEFAULT,
      fallbackToSimpleAuth);
  }

  private void checkAsyncCall() throws IOException {
    if (isAsynchronousMode()) {
      if (asyncCallCounter.incrementAndGet() > maxAsyncCalls) {
        asyncCallCounter.decrementAndGet();
        String errMsg = String.format(
            "Exceeded limit of max asynchronous calls: %d, " +
            "please configure %s to adjust it.",
            maxAsyncCalls,
            CommonConfigurationKeys.IPC_CLIENT_ASYNC_CALLS_MAX_KEY);
        throw new AsyncCallLimitExceededException(errMsg);
      }
    }
  }

  /**
   * Make a call, passing <code>rpcRequest</code>, to the IPC server defined by
   * <code>remoteId</code>, returning the rpc response.
   *
   * @param rpcKind
   * @param rpcRequest -  contains serialized method and method parameters
   * @param remoteId - the target rpc server
   * @param serviceClass - service class for RPC
   * @param fallbackToSimpleAuth - set to true or false during this method to
   *   indicate if a secure client falls back to simple auth
   * @returns the rpc response
   * Throws exceptions if there are network problems or if the remote code
   * threw an exception.
   *
   * 进行 RPC 调用，首先要创建一个代表着这次 RPC 的 Call 对象，然后从 connections 中找到通往同一个 Server、同一类服务的 Connection 线程
   * 如果还没有就加以建立；再通过该连接的 sendRpcRequest() 发送 RPC 请求
   */
  Writable call(RPC.RpcKind rpcKind, Writable rpcRequest,
      ConnectionId remoteId, int serviceClass,
      AtomicBoolean fallbackToSimpleAuth) throws IOException {
    final Call call = createCall(rpcKind, rpcRequest);  // 创建一个 Call 对象
    final Connection connection = getConnection(remoteId, call, serviceClass,
        fallbackToSimpleAuth);  // 如果尚未建立连接就与对方建立连接

    try {
      checkAsyncCall();
      try {
        connection.sendRpcRequest(call);                 // send the rpc request  发送 RPC 请求
      } catch (RejectedExecutionException e) {
        throw new IOException("connection has been closed", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("interrupted waiting to send rpc request to server", e);
        throw new IOException(e);
      }
    } catch(Exception e) {
      if (isAsynchronousMode()) {
        releaseAsyncCall();
      }
      throw e;
    }

    if (isAsynchronousMode()) {
      final AsyncGet<Writable, IOException> asyncGet
          = new AsyncGet<Writable, IOException>() {
        @Override
        public Writable get(long timeout, TimeUnit unit)
            throws IOException, TimeoutException{
          boolean done = true;
          try {
            final Writable w = getRpcResponse(call, connection, timeout, unit);  // 返回对方的回应
            if (w == null) {
              done = false;
              throw new TimeoutException(call + " timed out "
                  + timeout + " " + unit);
            }
            return w;
          } finally {
            if (done) {
              releaseAsyncCall();
            }
          }
        }

        @Override
        public boolean isDone() {
          synchronized (call) {
            return call.done;
          }
        }
      };

      ASYNC_RPC_RESPONSE.set(asyncGet);
      return null;
    } else {
      return getRpcResponse(call, connection, -1, null);
    }
  }

  /**
   * Check if RPC is in asynchronous mode or not.
   *
   * @returns true, if RPC is in asynchronous mode, otherwise false for
   *          synchronous mode.
   */
  @Unstable
  public static boolean isAsynchronousMode() {
    return asynchronousMode.get();
  }

  /**
   * Set RPC to asynchronous or synchronous mode.
   *
   * @param async
   *          true, RPC will be in asynchronous mode, otherwise false for
   *          synchronous mode
   */
  @Unstable
  public static void setAsynchronousMode(boolean async) {
    asynchronousMode.set(async);
  }

  private void releaseAsyncCall() {
    asyncCallCounter.decrementAndGet();
  }

  @VisibleForTesting
  int getAsyncCallCount() {
    return asyncCallCounter.get();
  }

  /** @return the rpc response or, in case of timeout, null. */
  private Writable getRpcResponse(final Call call, final Connection connection,
      final long timeout, final TimeUnit unit) throws IOException {
    synchronized (call) {
      while (!call.done) {
        try {
          AsyncGet.Util.wait(call, timeout, unit);
          if (timeout >= 0 && !call.done) {
            return null;
          }
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("Call interrupted");
        }
      }

      if (call.error != null) {
        if (call.error instanceof RemoteException) {
          call.error.fillInStackTrace();
          throw call.error;
        } else { // local exception
          InetSocketAddress address = connection.getRemoteAddress();
          throw NetUtils.wrapException(address.getHostName(),
                  address.getPort(),
                  NetUtils.getHostname(),
                  0,
                  call.error);
        }
      } else {
        return call.getRpcResponse();
      }
    }
  }

  // for unit testing only
  @InterfaceAudience.Private
  @InterfaceStability.Unstable
  Set<ConnectionId> getConnectionIds() {
    return connections.keySet();
  }
  
  /** Get a connection from the pool, or create a new one and add it to the
   * pool.  Connections to a given ConnectionId are reused. */
  // 建立连接
  private Connection getConnection(ConnectionId remoteId,
      Call call, int serviceClass, AtomicBoolean fallbackToSimpleAuth)
      throws IOException {
    if (!running.get()) {
      // the client is stopped
      throw new IOException("The client is stopped");
    }
    Connection connection;
    /* we could avoid this allocation for each RPC by having a  
     * connectionsId object and with set() method. We need to manage the
     * refs for keys in HashMap properly. For now its ok.
     */
    while (true) {
      // These lines below can be shorten with computeIfAbsent in Java8
      connection = connections.get(remoteId);
      if (connection == null) {
        connection = new Connection(remoteId, serviceClass);
        Connection existing = connections.putIfAbsent(remoteId, connection);
        if (existing != null) {
          connection = existing;
        }
      }

      if (connection.addCall(call)) {
        break;
      } else {
        // This connection is closed, should be removed. But other thread could
        // have already known this closedConnection, and replace it with a new
        // connection. So we should call conditional remove to make sure we only
        // remove this closedConnection.
        connections.remove(remoteId, connection);
      }
    }

    // If the server happens to be slow, the method below will take longer to
    // establish a connection.
    connection.setupIOstreams(fallbackToSimpleAuth);
    return connection;
  }
  
  /**
   * This class holds the address and the user ticket. The client connections
   * to servers are uniquely identified by <remoteAddress, protocol, ticket>
   */
  @InterfaceAudience.LimitedPrivate({"HDFS", "MapReduce"})
  @InterfaceStability.Evolving
  public static class ConnectionId {
    InetSocketAddress address;
    UserGroupInformation ticket;
    final Class<?> protocol;
    private static final int PRIME = 16777619;
    private final int rpcTimeout;
    private final int maxIdleTime; //connections will be culled if it was idle for 
    //maxIdleTime msecs
    private final RetryPolicy connectionRetryPolicy;
    private final int maxRetriesOnSasl;
    // the max. no. of retries for socket connections on time out exceptions
    private final int maxRetriesOnSocketTimeouts;
    private final boolean tcpNoDelay; // if T then disable Nagle's Algorithm
    private final boolean tcpLowLatency; // if T then use low-delay QoS
    private final boolean doPing; //do we need to send ping message
    private final int pingInterval; // how often sends ping to the server in msecs
    private String saslQop; // here for testing
    private final Configuration conf; // used to get the expected kerberos principal name
    
    ConnectionId(InetSocketAddress address, Class<?> protocol, 
                 UserGroupInformation ticket, int rpcTimeout,
                 RetryPolicy connectionRetryPolicy, Configuration conf) {
      this.protocol = protocol;
      this.address = address;
      this.ticket = ticket;
      this.rpcTimeout = rpcTimeout;
      this.connectionRetryPolicy = connectionRetryPolicy;

      this.maxIdleTime = conf.getInt(
          CommonConfigurationKeysPublic.IPC_CLIENT_CONNECTION_MAXIDLETIME_KEY,
          CommonConfigurationKeysPublic.IPC_CLIENT_CONNECTION_MAXIDLETIME_DEFAULT);
      this.maxRetriesOnSasl = conf.getInt(
          CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SASL_KEY,
          CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SASL_DEFAULT);
      this.maxRetriesOnSocketTimeouts = conf.getInt(
          CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SOCKET_TIMEOUTS_KEY,
          CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SOCKET_TIMEOUTS_DEFAULT);
      this.tcpNoDelay = conf.getBoolean(
          CommonConfigurationKeysPublic.IPC_CLIENT_TCPNODELAY_KEY,
          CommonConfigurationKeysPublic.IPC_CLIENT_TCPNODELAY_DEFAULT);
      this.tcpLowLatency = conf.getBoolean(
          CommonConfigurationKeysPublic.IPC_CLIENT_LOW_LATENCY,
          CommonConfigurationKeysPublic.IPC_CLIENT_LOW_LATENCY_DEFAULT
          );
      this.doPing = conf.getBoolean(
          CommonConfigurationKeys.IPC_CLIENT_PING_KEY,
          CommonConfigurationKeys.IPC_CLIENT_PING_DEFAULT);
      this.pingInterval = (doPing ? Client.getPingInterval(conf) : 0);
      this.conf = conf;
    }
    
    InetSocketAddress getAddress() {
      return address;
    }
    
    Class<?> getProtocol() {
      return protocol;
    }
    
    UserGroupInformation getTicket() {
      return ticket;
    }
    
    private int getRpcTimeout() {
      return rpcTimeout;
    }
    
    int getMaxIdleTime() {
      return maxIdleTime;
    }
    
    public int getMaxRetriesOnSasl() {
      return maxRetriesOnSasl;
    }

    /** max connection retries on socket time outs */
    public int getMaxRetriesOnSocketTimeouts() {
      return maxRetriesOnSocketTimeouts;
    }

    /** disable nagle's algorithm */
    boolean getTcpNoDelay() {
      return tcpNoDelay;
    }

    /** use low-latency QoS bits over TCP */
    boolean getTcpLowLatency() {
      return tcpLowLatency;
    }

    boolean getDoPing() {
      return doPing;
    }
    
    int getPingInterval() {
      return pingInterval;
    }
    
    @VisibleForTesting
    String getSaslQop() {
      return saslQop;
    }
    
    /**
     * Returns a ConnectionId object. 
     * @param addr Remote address for the connection.
     * @param protocol Protocol for RPC.
     * @param ticket UGI
     * @param rpcTimeout timeout
     * @param conf Configuration object
     * @return A ConnectionId instance
     * @throws IOException
     */
    static ConnectionId getConnectionId(InetSocketAddress addr,
        Class<?> protocol, UserGroupInformation ticket, int rpcTimeout,
        RetryPolicy connectionRetryPolicy, Configuration conf) throws IOException {

      if (connectionRetryPolicy == null) {
        final int max = conf.getInt(
            CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY,
            CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_DEFAULT);
        final int retryInterval = conf.getInt(
            CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_RETRY_INTERVAL_KEY,
            CommonConfigurationKeysPublic
                .IPC_CLIENT_CONNECT_RETRY_INTERVAL_DEFAULT);

        connectionRetryPolicy = RetryPolicies.retryUpToMaximumCountWithFixedSleep(
            max, retryInterval, TimeUnit.MILLISECONDS);
      }

      return new ConnectionId(addr, protocol, ticket, rpcTimeout,
          connectionRetryPolicy, conf);
    }
    
    static boolean isEqual(Object a, Object b) {
      return a == null ? b == null : a.equals(b);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (obj instanceof ConnectionId) {
        ConnectionId that = (ConnectionId) obj;
        return isEqual(this.address, that.address)
            && this.doPing == that.doPing
            && this.maxIdleTime == that.maxIdleTime
            && isEqual(this.connectionRetryPolicy, that.connectionRetryPolicy)
            && this.pingInterval == that.pingInterval
            && isEqual(this.protocol, that.protocol)
            && this.rpcTimeout == that.rpcTimeout
            && this.tcpNoDelay == that.tcpNoDelay
            && isEqual(this.ticket, that.ticket);
      }
      return false;
    }
    
    @Override
    public int hashCode() {
      int result = connectionRetryPolicy.hashCode();
      result = PRIME * result + ((address == null) ? 0 : address.hashCode());
      result = PRIME * result + (doPing ? 1231 : 1237);
      result = PRIME * result + maxIdleTime;
      result = PRIME * result + pingInterval;
      result = PRIME * result + ((protocol == null) ? 0 : protocol.hashCode());
      result = PRIME * result + rpcTimeout;
      result = PRIME * result + (tcpNoDelay ? 1231 : 1237);
      result = PRIME * result + ((ticket == null) ? 0 : ticket.hashCode());
      return result;
    }
    
    @Override
    public String toString() {
      return address.toString();
    }
  }  

  /**
   * Returns the next valid sequential call ID by incrementing an atomic counter
   * and masking off the sign bit.  Valid call IDs are non-negative integers in
   * the range [ 0, 2^31 - 1 ].  Negative numbers are reserved for special
   * purposes.  The values can overflow back to 0 and be reused.  Note that prior
   * versions of the client did not mask off the sign bit, so a server may still
   * see a negative call ID if it receives connections from an old client.
   * 
   * @return next call ID
   */
  public static int nextCallId() {
    return callIdCounter.getAndIncrement() & 0x7FFFFFFF;
  }

  @Override
  @Unstable
  public void close() throws Exception {
    stop();
  }

  /** Manages the input and output streams for an IPC connection.
   *  Only exposed for use by SaslRpcClient.
   */
  @InterfaceAudience.Private
  public static class IpcStreams implements Closeable, Flushable {
    private DataInputStream in;
    public DataOutputStream out;
    private int maxResponseLength;
    private boolean firstResponse = true;

    IpcStreams(Socket socket, int maxResponseLength) throws IOException {
      this.maxResponseLength = maxResponseLength;
      setInputStream(
          new BufferedInputStream(NetUtils.getInputStream(socket)));
      setOutputStream(
          new BufferedOutputStream(NetUtils.getOutputStream(socket)));
    }

    void setSaslClient(SaslRpcClient client) throws IOException {
      // Wrap the input stream in a BufferedInputStream to fill the buffer
      // before reading its length (HADOOP-14062).
      setInputStream(new BufferedInputStream(client.getInputStream(in)));
      setOutputStream(client.getOutputStream(out));
    }

    private void setInputStream(InputStream is) {
      this.in = (is instanceof DataInputStream)
          ? (DataInputStream)is : new DataInputStream(is);
    }

    private void setOutputStream(OutputStream os) {
      this.out = (os instanceof DataOutputStream)
          ? (DataOutputStream)os : new DataOutputStream(os);
    }

    public ByteBuffer readResponse() throws IOException {
      int length = in.readInt();
      if (firstResponse) {
        firstResponse = false;
        // pre-rpcv9 exception, almost certainly a version mismatch.
        if (length == -1) {
          in.readInt(); // ignore fatal/error status, it's fatal for us.
          throw new RemoteException(WritableUtils.readString(in),
                                    WritableUtils.readString(in));
        }
      }
      if (length <= 0) {
        throw new RpcException("RPC response has invalid length");
      }
      if (maxResponseLength > 0 && length > maxResponseLength) {
        throw new RpcException("RPC response exceeds maximum data length");
      }
      ByteBuffer bb = ByteBuffer.allocate(length);
      in.readFully(bb.array());
      return bb;
    }

    public void sendRequest(byte[] buf) throws IOException {
      out.write(buf);
    }

    @Override
    public void flush() throws IOException {
      out.flush();
    }

    @Override
    public void close() {
      IOUtils.closeStream(out);
      IOUtils.closeStream(in);
    }
  }
}
