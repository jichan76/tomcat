package com.example.attendance.servlet;

import com.google.gson.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.logging.Logger;

@WebServlet("/updateManualAttendance")
public class UpdateManualAttendanceServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(UpdateManualAttendanceServlet.class.getName());

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out = response.getWriter();
        JsonObject jsonResponse = new JsonObject();

        // 파라미터 로그
        String subjectIdStr = request.getParameter("subjectId");
        String weekStr = request.getParameter("week");
        if (subjectIdStr == null || weekStr == null) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "Missing subjectId or week");
            out.print(jsonResponse.toString());
            return;
        }

        int subjectId = Integer.parseInt(subjectIdStr);
        int week = Integer.parseInt(weekStr);

        // 바디(JSON) 읽고 로그
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        String requestBody = sb.toString();
        JsonArray studentsJson;
        try {
            studentsJson = JsonParser.parseString(requestBody).getAsJsonArray();
        } catch (Exception ex) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "Invalid JSON body");
            out.print(jsonResponse.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            String updateSql = "UPDATE attendance_records SET status = ? " +
                    "WHERE student_id = ? AND subject_id = ? AND week_number = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                for (JsonElement elem : studentsJson) {
                    JsonObject studentObj = elem.getAsJsonObject();
                    String studentId = studentObj.get("studentId").getAsString();
                    String status = studentObj.get("status").getAsString();
                    if ("지각/조퇴".equals(status)) {
                        status = "지각_조퇴";
                    }
                    pstmt.setString(1, status);
                    pstmt.setString(2, studentId);
                    pstmt.setInt(3, subjectId);
                    pstmt.setInt(4, week);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
            jsonResponse.addProperty("result", "success");
        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", e.getMessage());
            e.printStackTrace();
        }
        out.print(jsonResponse.toString());
    }
}
