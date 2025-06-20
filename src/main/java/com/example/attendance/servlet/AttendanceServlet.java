package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

            // 1. 교수 BSSID와 일치하는 수강 과목 찾기
            String selectSubjectSql =
                    "SELECT s.subject_id, s.name " +
                            "FROM enrollments e " +
                            "JOIN subjects s ON e.subject_id = s.subject_id " +
                            "WHERE e.student_id = ? " +
                            "AND s.professor_bssid = ?";

            String actualSubjectId = null;
            String subjectName = null;
            int subjectCount = 0;

            try (PreparedStatement ps = conn.prepareStatement(selectSubjectSql)) {
                ps.setString(1, studentId);
                ps.setString(2, bssid);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    subjectCount++;
                    actualSubjectId = rs.getString("subject_id");
                    subjectName = rs.getString("name");
                }
            }

            if (subjectCount == 0) {
                jsonResponse.addProperty("result", "fail");
                jsonResponse.addProperty("message", "현재 출석 가능한 수업이 없습니다.");
                out.print(jsonResponse.toString());
                return;
            } else if (subjectCount > 1) {
                jsonResponse.addProperty("result", "fail");
                jsonResponse.addProperty("message", "여러 과목이 동시에 매칭됩니다. 관리자에게 문의하세요.");
                out.print(jsonResponse.toString());
                return;
            }

            // 2. 학기 시작일을 DB에서 가져옴
            String getStartDateSql = "SELECT start_date FROM semester_info";
            Date startDate = null;
            try (PreparedStatement ps = conn.prepareStatement(getStartDateSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    startDate = rs.getDate("start_date");
                }
            }
            if (startDate == null) {
                jsonResponse.addProperty("result", "error");
                jsonResponse.addProperty("message", "학기 시작일 정보가 없습니다.");
                out.print(jsonResponse.toString());
                return;
            }

            // 3. 오늘 날짜와 학기 시작일 차이로 주차 계산
            LocalDate today = LocalDate.now();
            LocalDate semesterStart = startDate.toLocalDate();
            long days = ChronoUnit.DAYS.between(semesterStart, today);
            int weekNumber = (int)Math.ceil((days + 1) / 7.0);

            // 4. 출석 INSERT
            String insertSql =
                    "INSERT INTO attendance_records " +
                            "(student_id, subject_id, attendance_datetime, status, bssid, android_id, week_number) " +
                            "VALUES (?, ?, (SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul'), '출석', ?, ?, ?)";

            try (PreparedStatement ps2 = conn.prepareStatement(insertSql)) {
                ps2.setString(1, studentId);
                ps2.setString(2, actualSubjectId);
                ps2.setString(3, bssid);
                ps2.setString(4, androidId);
                ps2.setInt(5, weekNumber);
                ps2.executeUpdate();
            }

            jsonResponse.addProperty("result", "success");
            jsonResponse.addProperty("subject_name", subjectName);
            jsonResponse.addProperty("week_number", weekNumber);

        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", e.getMessage());
            e.printStackTrace();
        }
        out.print(jsonResponse.toString());
    }
}
