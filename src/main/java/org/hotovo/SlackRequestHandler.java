package org.hotovo;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.request.HttpRequest;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import static java.lang.String.format;

public class SlackRequestHandler implements HttpHandler {

    private static final int[] FPC_2019 = {176, 160, 168, 160, 168, 160, 176, 168, 168, 184, 160, 152};
    private static final double HOUR = 3600.0;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestMethod = exchange.getRequestMethod();
        if (requestMethod.equalsIgnoreCase("POST")) {
            JsonNode jsonNode = new JsonNode(readRequestBody(exchange.getRequestBody()));
            if ("url_verification".equals(jsonNode.getObject().getString("type"))) {
                handleChallenge(exchange, jsonNode.getObject().getString("challenge"));
            } else {
                handleRequest(exchange, jsonNode);
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        } else {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        }
    }

    private static void handleChallenge(HttpExchange exchange, String challenge) throws IOException {
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "text/plain");

        OutputStream responseBody = exchange.getResponseBody();
        responseBody.write(challenge.getBytes());
        responseBody.close();
    }

    private static void handleRequest(HttpExchange exchange, JsonNode jsonBody) {
        System.out.println("handling request");
        findUsers();
    }

    private static void findUsers() {
        YearMonth currentMonth = YearMonth.now();
        try {
            // FIND ALL USERS OF PARTICULAR GROUP
            // 50 results per page
            HttpResponse<JsonNode> users = getJsonResponse(String.format("https://%s.atlassian.net/rest/api/3/group/member?groupname=jira-core-users",
                    System.getenv("jira.instance")));
            System.out.println("users found");
            for (Object user : users.getBody().getObject().getJSONArray("values")) {
                if (((JSONObject) user).getString("emailAddress").contains("connect.atlassian.com")) { //TODO maybe better to invert this with specific domain, i.e. @hotovo.org
                    continue;
                }
                findUserIssues(currentMonth, (JSONObject) user);
            }

        } catch (UnirestException e) {
            e.printStackTrace();
        }
    }

    private static void findUserIssues(YearMonth currentMonth, JSONObject user) throws UnirestException {
        double loggedHours = 0;
        System.out.println(user.getString("emailAddress") + ": " + user.getString("accountId"));

        // FIND ALL RELEVANT USER ISSUES BASED ON PROJECT
        // 50 results per page
        // TODO contractors only project, full-timers project + HR task)
        HttpResponse<JsonNode> issues = getJsonResponse(String.format("https://%s.atlassian.net/rest/api/3/search",
                System.getenv("jira.instance")),
                Collections.singletonMap("jql", format("project=WL AND assignee=%s AND worklogDate >= %s AND worklogDate <= %s",
                        user.getString("accountId"), currentMonth.atDay(1).toString(),
                        currentMonth.atEndOfMonth().toString())));

        // COUNT TIME OF ALL WORKLOGS
        // 1048576 results per page
        for (Object issue : issues.getBody().getArray().getJSONObject(0).getJSONArray("issues")) {
            loggedHours += countWorklogsForCurrentMonth(currentMonth.getMonth(), loggedHours, (JSONObject) issue);
        }
        System.out.println("-------------------------------------------------");
        System.out.println(format("logged: %.2f hours, FPC for %s is: %d hours", loggedHours, currentMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.US), FPC_2019[currentMonth.getMonth().ordinal()]));
        if (loggedHours < FPC_2019[currentMonth.getMonth().ordinal()]) {
            sendSlackNotificaton(currentMonth.getMonth(), user, loggedHours);
        } else {
            System.out.println("Well done!");
        }
    }

    private static void sendSlackNotificaton(Month currentMonth, JSONObject user, double loggedHours) throws UnirestException {
        System.out.println("Notification sent.\n");
        Unirest.post(System.getenv("slack.post.message.url"))
                .header("Authorization", System.getenv("slack.bot.token"))
                .header("Content-type", "application/json")
                .body(String.format("{\"channel\":\"%s\", \"text\":\"User %s, logged: %.2f hours, but baseline for %s is: %d hours!\"}",
                        System.getenv("slack.channel"), user.getString("emailAddress"),
                        loggedHours, currentMonth.getDisplayName(TextStyle.FULL, Locale.US),
                        FPC_2019[currentMonth.ordinal()]))
                .asString();
    }

    private static double countWorklogsForCurrentMonth(Month currentMonth, double loggedHours, JSONObject issue) throws UnirestException {
        System.out.println("\t" + issue.getString("key") + " " + issue.getString("id"));
        HttpResponse<JsonNode> worklogs = getJsonResponse(String.format("https://%s.atlassian.net/rest/api/3/issue/%s/worklog",
                System.getenv("jira.instance"), issue.getString("id")));
        for (Object worklog : worklogs.getBody().getArray().getJSONObject(0).getJSONArray("worklogs")) {
            LocalDate worklogDate = LocalDate.parse(((JSONObject) worklog).getString("started").substring(0, 10));
            if (currentMonth.equals(worklogDate.getMonth())) {
                int timeSpentSeconds = ((JSONObject) worklog).getInt("timeSpentSeconds");
                loggedHours += toHours(timeSpentSeconds);
                System.out.println(format("\t\t%s: %d seconds, %.2f hours",
                        worklogDate,
                        timeSpentSeconds,
                        toHours(timeSpentSeconds)));
            }
        }
        return loggedHours;
    }

    private static String readRequestBody(InputStream inputStream) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int result = bis.read();
        while (result != -1) {
            buf.write((byte) result);
            result = bis.read();
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private static double toHours(int seconds) {
        return seconds / HOUR;
    }

    private static HttpResponse<JsonNode> getJsonResponse(String url, Map<String, Object> queryParams) throws UnirestException {
        return authenticate(createGetRequest(url).queryString(queryParams)).asJson();
    }

    private static HttpResponse<JsonNode> getJsonResponse(String url) throws UnirestException {
        return authenticate(createGetRequest(url)).asJson();
    }

    private static HttpRequest authenticate(HttpRequest request) {
        return request.header("Accept", "application/json")
                .basicAuth(System.getenv("user"), System.getenv("token"));
    }

    private static HttpRequest createGetRequest(String url) {
        return Unirest.get(url);
    }
}
