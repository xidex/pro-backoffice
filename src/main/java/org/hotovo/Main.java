package org.hotovo;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class Main {

    public static void main(String[] args) {
        try {
            String port = System.getenv("PORT");
            HttpServer server = HttpServer.create(new InetSocketAddress(Integer.valueOf(port)), 0);
            server.createContext("/", new SlackRequestHandler());
            server.setExecutor(null); // creates a default executor
            server.start();
            System.out.println("Server is listening on port " + port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
