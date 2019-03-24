import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.mashape.unirest.request.HttpRequest;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;

public class Main {

    private static final int[] FPC_2019 = {176, 160, 168, 160, 168, 160, 176, 168, 168, 184, 160, 152};
    private static final double HOUR = 3600.0;
    private static Properties prop = new Properties();

    public static void main(String[] args) {
        try {
            prop = new Properties();
            InputStream in = Main.class.getResourceAsStream("application.properties");
            prop.load(in);
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        Month currentMonth = LocalDate.now().getMonth();
        try {
            // FIND ALL USERS OF PARTICULAR GROUP
            // 50 results per page
            HttpResponse<JsonNode> users = getJsonResponse(String.format("https://%s.atlassian.net/rest/api/3/group/member?groupname=jira-core-users",
                    prop.getProperty("jira.instance")));

            for (Object user : users.getBody().getObject().getJSONArray("values")) {
                if (((JSONObject) user).getString("emailAddress").contains("connect.atlassian.com")) { //TODO maybe better to invert this with specific domain, i.e. @hotovo.org
                    continue;
                }
                double loggedHours = 0;
                System.out.println(((JSONObject) user).getString("emailAddress") + ": " + ((JSONObject) user).getString("accountId"));

                // FIND ALL RELEVANT USER ISSUES BASED ON PROJECT
                // 50 results per page
                // TODO contractors only project, full-timers project + HR task)
                HttpResponse<JsonNode> issues = getJsonResponse(String.format("https://%s.atlassian.net/rest/api/3/search", prop.getProperty("jira.instance")),
                        Collections.singletonMap("jql", format("project=WL AND assignee=%s", ((JSONObject) user).getString("accountId"))));

                // COUNT TIME OF ALL WORKLOGS
                // 1048576 results per page
                for (Object issue : issues.getBody().getArray().getJSONObject(0).getJSONArray("issues")) {
                    System.out.println("\t" + ((JSONObject) issue).getString("key") + " " + ((JSONObject) issue).getString("id"));
                    HttpResponse<JsonNode> worklogs = getJsonResponse(String.format("https://%s.atlassian.net/rest/api/3/issue/%s/worklog",
                            prop.getProperty("jira.instance"), ((JSONObject) issue).getString("id")));
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
                }
                System.out.println("-------------------------------------------------");
                System.out.println(format("logged: %.2f hours, FPC for %s is: %d hours", loggedHours, currentMonth.getDisplayName(TextStyle.FULL, Locale.US), FPC_2019[currentMonth.ordinal()]));
                if (loggedHours < FPC_2019[currentMonth.ordinal()]) {
                    System.out.println("Notification sent.\n");
                    Unirest.post(prop.getProperty("slack.webhook.url"))
                            .header("Content-type", "application/json")
                            .body(String.format("{\"text\":\"User %s, logged: %.2f hours, but baseline for %s is: %d hours!\"}",
                                    ((JSONObject) user).getString("emailAddress"), loggedHours, currentMonth.getDisplayName(TextStyle.FULL, Locale.US), FPC_2019[currentMonth.ordinal()]))
                            .asString();
                } else {
                    System.out.println("Well done!");
                }
            }

        } catch (UnirestException e) {
            e.printStackTrace();
        }
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
                .basicAuth(prop.getProperty("user"), prop.getProperty("token"));
    }

    private static HttpRequest createGetRequest(String url) {
        return Unirest.get(url);
    }
}
