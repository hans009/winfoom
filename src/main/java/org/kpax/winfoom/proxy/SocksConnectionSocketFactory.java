/*
 * Copyright (c) 2020. Eugen Covaci
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.kpax.winfoom.proxy;

import org.apache.commons.lang3.Validate;
import org.apache.http.HttpHost;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.kpax.winfoom.config.UserConfig;
import org.kpax.winfoom.util.HttpUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class SocksConnectionSocketFactory implements ConnectionSocketFactory {

    private UserConfig userConfig;

    public SocksConnectionSocketFactory(UserConfig userConfig) {
        Validate.notNull(userConfig);
        this.userConfig = userConfig;
    }

    @Override
    public Socket createSocket(final HttpContext context) throws IOException {
        InetSocketAddress socketAddress = (InetSocketAddress) context.getAttribute(HttpUtils.SOCKS_ADDRESS);
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, socketAddress);
        Socket socket = new Socket(proxy);
        if (userConfig.isSocks4()) {
            try {
                HttpUtils.setSocks4(socket);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return socket;
    }

    @Override
    public Socket connectSocket(
            final int connectTimeout,
            final Socket socket,
            final HttpHost host,
            final InetSocketAddress remoteAddress,
            final InetSocketAddress localAddress,
            final HttpContext context) throws IOException, ConnectTimeoutException {
        Socket currentSocket = socket != null ? socket: createSocket(context);
        if (localAddress != null) {
            currentSocket.bind(localAddress);
        }
        try {
            currentSocket.connect(remoteAddress, connectTimeout);
        } catch (SocketTimeoutException ex) {
            throw new ConnectTimeoutException(ex, host, remoteAddress.getAddress());
        }
        return currentSocket;
    }

}