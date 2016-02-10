/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package net.minder.httpcap;

import org.apache.http.*;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.DefaultBHttpServerConnectionFactory;
import org.apache.http.protocol.*;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.PropertyConfigurator;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Basic, yet fully functional and spec compliant, HTTP/1.1 file server.
 */
public class HttpCap {

  private static int DEFAULT_PORT = 8888;

  public static void main(String[] args) throws Exception {
    PropertyConfigurator.configure( ClassLoader.getSystemResourceAsStream( "log4j.properties" ) );

    int port = DEFAULT_PORT;

    if( args.length > 0 ) {
      try {
        port = Integer.parseInt( args[0] );
      } catch ( NumberFormatException nfe ) {
        port = DEFAULT_PORT;
      }
    }

    HttpProcessor httpproc = HttpProcessorBuilder.create()
        .add( new ResponseDate() )
        .add( new ResponseServer("HttpCap/1.1") )
        .add( new ResponseContent() )
        .add( new ResponseConnControl() ).build() ;

    UriHttpRequestHandlerMapper reqistry = new UriHttpRequestHandlerMapper();
    reqistry.register( "*", new HttpCapHandler() );

    // Set up the HTTP service
    HttpService httpService = new HttpService( httpproc, reqistry );

    SSLServerSocketFactory sf = null;
//    if (port == 8443) {
//      // Initialize SSL context
//      ClassLoader cl = HttpCap.class.getClassLoader();
//      URL url = cl.getResource("my.keystore");
//      if (url == null) {
//        System.out.println("Keystore not found");
//        System.exit(1);
//      }
//      KeyStore keystore  = KeyStore.getInstance("jks");
//      keystore.load(url.openStream(), "secret".toCharArray());
//      KeyManagerFactory kmfactory = KeyManagerFactory.getInstance(
//          KeyManagerFactory.getDefaultAlgorithm());
//      kmfactory.init(keystore, "secret".toCharArray());
//      KeyManager[] keymanagers = kmfactory.getKeyManagers();
//      SSLContext sslcontext = SSLContext.getInstance("TLS");
//      sslcontext.init(keymanagers, null, null);
//      sf = sslcontext.getServerSocketFactory();
//    }

    Thread t = new RequestListenerThread( port, httpService, sf );
    t.setDaemon(false);
    t.start();
  }

  static class HttpCapHandler implements HttpRequestHandler  {

    public HttpCapHandler() {
      super();
    }

    public void handle(
        final HttpRequest request,
        final HttpResponse response,
        final HttpContext context) throws HttpException, IOException {
      System.out.println( request.getRequestLine() );
      Header headers[] = request.getAllHeaders();
      if( headers != null ) {
        for( Header header : headers ) {
          System.out.print( "  " );
          System.out.print( header.getName() );
          System.out.print( "=[" );
          HeaderElement elements[] = header.getElements();
          if( elements != null ) {
            for( int i=0; i<elements.length; i++ ) {
              HeaderElement element = elements[i];
              if( i > 0 ) {
                String value = element.getValue();
                if( value != null ) {
                  System.out.print( "=" );
                  System.out.print( value );
                }
                NameValuePair[] params = element.getParameters();
                if( params != null ) {
                  for( int j=0; j<params.length; j++ ) {
                    System.out.print( ";" );
                    NameValuePair param = params[j];
                    System.out.print( param.getName() );
                    String paramValue = param.getValue();
                    if( paramValue != null ) {
                      System.out.print( "=" );
                      System.out.print( param.getValue() );
                    }
                  }
                }
                System.out.print( "," );
              }
              System.out.print( element.getName() );
            }
          }
          System.out.println( "]" );
        }
      }
      if (request instanceof HttpEntityEnclosingRequest) {
        HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
        System.out.println( EntityUtils.toString( entity ) );
      }
      response.setStatusCode( HttpStatus.SC_OK );
    }

  }

  static class RequestListenerThread extends Thread {

    private final HttpConnectionFactory<DefaultBHttpServerConnection> connFactory;
    private final ServerSocket serversocket;
    private final HttpService httpService;

    public RequestListenerThread(
        final int port,
        final HttpService httpService,
        final SSLServerSocketFactory sf) throws IOException {
      this.connFactory = DefaultBHttpServerConnectionFactory.INSTANCE;
      this.serversocket = sf != null ? sf.createServerSocket(port) : new ServerSocket(port);
      this.httpService = httpService;
    }

    @Override
    public void run() {
      System.out.println("Listening on port " + this.serversocket.getLocalPort());
      while (!Thread.interrupted()) {
        try {
          // Set up HTTP connection
          Socket socket = this.serversocket.accept();
          //System.out.println("Incoming connection from " + socket.getInetAddress());
          HttpServerConnection conn = this.connFactory.createConnection(socket);

          // Start worker thread
          Thread t = new WorkerThread(this.httpService, conn);
          t.setDaemon(true);
          t.start();
        } catch (InterruptedIOException ex) {
          break;
        } catch (IOException e) {
          System.err.println("I/O error initialising connection thread: "+ e.getMessage());
          break;
        }
      }
    }
  }

  static class WorkerThread extends Thread {

    private final HttpService httpservice;
    private final HttpServerConnection conn;

    public WorkerThread(
        final HttpService httpservice,
        final HttpServerConnection conn) {
      super();
      this.httpservice = httpservice;
      this.conn = conn;
    }

    @Override
    public void run() {
      //System.out.println("New connection thread");
      HttpContext context = new BasicHttpContext(null);
      try {
        while (!Thread.interrupted() && this.conn.isOpen()) {
          this.httpservice.handleRequest(this.conn, context);
        }
      } catch (ConnectionClosedException ex) {
        //System.err.println("Client closed connection");
      } catch (IOException ex) {
        //System.err.println("I/O error: " + ex.getMessage());
      } catch (HttpException ex) {
        //System.err.println("Unrecoverable HTTP protocol violation: " + ex.getMessage());
      } finally {
        try {
          this.conn.shutdown();
        } catch (IOException ignore) {}
      }
    }

  }

}