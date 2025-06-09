package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

import com.google.gson.JsonObject;

@WebServlet("/AttendanceServlet")
public class AttendanceServlet extends HttpServlet {

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

        String studentId = request.getParameter("student_id");
        String bssid = request.getParameter("bssid");
        String androidId = request.getParameter("android_id");

        if (studentId == null || bssid == null || androidId == null) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "Missing parameters");
            out.print(jsonResponse.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            // 교수 BSSID와 일치하는 수강 과목 찾기
            String sql = "SELECT s.subject_id " +
                    "FROM subjects s " +
                    "JOIN enrollments e ON s.subject_id = e.subject_id " +
                    "WHERE e.student_id = ? AND s.professor_bssid = ? " +
                    "AND ROWNUM = 1"; // 오라클용 LIMIT 1

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, studentId);
                pstmt.setString(2, bssid);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    int subjectId = rs.getInt("subject_id");
                    String status = "출석";

                    String insertSql = "INSERT INTO attendance_records (student_id, subject_id, attendance_datetime, status, bssid, android_id) " +
                            "VALUES (?, ?, SYSDATE, ?, ?, ?)";

                    try (PreparedStatement insertPstmt = conn.prepareStatement(insertSql)) {
                        insertPstmt.setString(1, studentId);
                        insertPstmt.setInt(2, subjectId);
                        insertPstmt.setString(3, status);
                        insertPstmt.setString(4, bssid);
                        insertPstmt.setString(5, androidId);
                        insertPstmt.executeUpdate();

                        jsonResponse.addProperty("result", "success");
                    }
                } else {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "No matching subject found for given BSSID");
                }
            }
        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", e.getMessage());
            e.printStackTrace();
        }
        out.print(jsonResponse.toString());
    }
}