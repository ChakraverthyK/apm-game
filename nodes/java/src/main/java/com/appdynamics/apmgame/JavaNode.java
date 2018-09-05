package com.appdynamics.apmgame;

import net.sf.ehcache.Element;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;

import javax.json.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.StringReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.concurrent.ThreadLocalRandom;

/*import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;*/

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;

public class JavaNode {

    protected static Cache cache;

    public static void main(String[] args) throws Exception {

        int port = 8080;
        CacheManager cacheManager;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        JsonReader jsonReader = Json.createReader(new StringReader(System.getenv("APP_CONFIG")));
        JsonObject config = jsonReader.readObject();

        jsonReader = Json.createReader(new StringReader(System.getenv("APM_CONFIG")));
        JsonObject apmConfig = jsonReader.readObject();

        CacheManager cm = CacheManager.getInstance();

        cm.addCache("cache1");

        cache = cm.getCache("cache1");

        Server server = new Server(port);
        ServletHandler handler = new ServletHandler();
        server.setHandler(handler);

        NodeServlet.setConfig(config, apmConfig);

        handler.addServletWithMapping(NodeServlet.class, "/*");

        server.start();
        server.join();
    }

    @SuppressWarnings("serial")
    public static class NodeServlet extends HttpServlet {
        protected static JsonObject config;
        protected static JsonObject apmConfig;
        protected static JsonObject endpoints;


        public static void setConfig(JsonObject config, JsonObject apmConfig) {
            NodeServlet.config = config;
            NodeServlet.apmConfig = apmConfig;
            NodeServlet.endpoints = config.getJsonObject("endpoints").getJsonObject("http");
        }

        protected String buildResponse(int timeout) {
            long start = System.currentTimeMillis();
            long finish = start;
            String response = "";
            while(finish - start < timeout) {
                response += " ";
                finish = System.currentTimeMillis();
            }
            return response.length() + "slow response";
        }

        protected String loadFromCache(int timeout) {
            long start = System.currentTimeMillis();
            long finish = start;
            int i = 0;
            Integer element = new Integer(0);
            while(finish - start < timeout) {
                i++;
                element = new Integer(i);
                cache.putIfAbsent(new Element(element, i));
                finish = System.currentTimeMillis();
            }
            return "Cache result: " + cache.get(element).toString();
        }

        protected String callRemote(String call, boolean catchExceptions, int remoteTimeout) throws IOException {
            try {
                URL url = new URL(call);
                // return new Scanner( url.openStream() ).useDelimiter( "\\Z" ).next();
                HttpURLConnection con = (HttpURLConnection) url.openConnection();

                con.setConnectTimeout(remoteTimeout);
                con.setReadTimeout(remoteTimeout);
                con.setRequestProperty("Content-Type", "application/json");

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(con.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                return response.toString();

            } catch (IOException e) {
                if(catchExceptions) {
                    return e.getMessage();
                }
                throw e;
            }
        }

        protected String processCall(String call, boolean catchExceptions, int remoteTimeout) throws IOException {
            if (call.startsWith("sleep")) {
                int timeout = Integer.parseInt(call.split(",")[1]);
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {

                }
                return "Slept for " + timeout;
            }

            if (call.startsWith("slow")) {
                int timeout = Integer.parseInt(call.split(",")[1]);
                return this.buildResponse(timeout);
            }

            if (call.startsWith("cache")) {
                int timeout = Integer.parseInt(call.split(",")[1]);
                return this.loadFromCache(timeout);
            }

            if (call.startsWith("http://")) {
                return this.callRemote(call, catchExceptions, remoteTimeout);
            }
            if (call.startsWith("error")) {
                throw new HttpException(500, "error");
            }
            return ":" + call + " is not supported";
        }

        protected String preProcessCall(JsonValue call) throws IOException {

            boolean catchExceptions = true;
            int remoteTimeout = Integer.MAX_VALUE;

            if (call.getValueType() == JsonValue.ValueType.ARRAY) {
                JsonArray arr = (JsonArray) call;
                int index = ThreadLocalRandom.current().nextInt(arr.size());
                call = arr.get(index);
            }
            if (call.getValueType() == JsonValue.ValueType.OBJECT) {
                JsonObject obj = (JsonObject) call;
                call = obj.getJsonString("call");
                if(obj.containsKey("probability")) {
                    double probability = obj.getJsonNumber("probability").doubleValue();
                    if (probability * 100 < ThreadLocalRandom.current().nextInt(100)) {
                        return call + " was not probable";
                    }
                }
                if(obj.containsKey("catchExceptions")) {
                    catchExceptions = obj.getBoolean("catchExceptions");
                }
                if(obj.containsKey("remoteTimeout")) {
                    remoteTimeout = obj.getInt("remoteTimeout");
                }
             }
            return this.processCall(((JsonString) call).getString(), catchExceptions, remoteTimeout);
        }

        public void handleEndpoint(HttpServletResponse response, JsonArray endpoint, boolean withEum) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);

            StringBuilder result = new StringBuilder();

            for (JsonValue entry : endpoint) {
                result.append(this.preProcessCall(entry));
            }

            if(withEum) {
                response.getWriter().println("<!doctype html><html lang=\"en\"><head><title>" + NodeServlet.config.getString("name") + "</title><script>window['adrum-start-time'] = new Date().getTime();window['adrum-config'] = " + NodeServlet.apmConfig.getJsonObject("eum") + " </script><script src='//cdn.appdynamics.com/adrum/adrum-latest.js'></script><body>" + result);
            } else {
                response.getWriter().println(result);
            }
        }

        @Override
        protected void doGet(HttpServletRequest request,
                             HttpServletResponse response) throws ServletException,
                IOException {
            String endpoint = request.getRequestURI().toString();

            boolean withEum = NodeServlet.apmConfig.containsKey("eum");

            String contentType = request.getContentType();
            if (contentType == null) {
                response.setContentType("text/html;charset=utf-8");
            } else if(contentType == "application/json") {
                response.setContentType(contentType);
                withEum = false;
            }

            try {
                if (NodeServlet.endpoints.containsKey(endpoint)) {
                    this.handleEndpoint(response, NodeServlet.endpoints.getJsonArray(endpoint), withEum);
                } else if (NodeServlet.endpoints.containsKey(endpoint.substring(1))) {
                    this.handleEndpoint(response, NodeServlet.endpoints.getJsonArray(endpoint.substring(1)), withEum);
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.getWriter().println(404);
                }
            } catch (HttpException e) {
                response.setStatus(e.getCode());
                response.getWriter().println(e.getMessage());
            } catch (IOException e) {
                response.setStatus(500);
                response.getWriter().println(e.getMessage());
            }
        }
    }
}
