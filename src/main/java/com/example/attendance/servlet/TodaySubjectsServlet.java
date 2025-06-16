package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import java.time.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import com.google.gson.*;

@WebServlet("/TodaySubjectsServlet")
public class TodaySubjectsServlet extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url      = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user     = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out       = response.getWriter();
        JsonObject json       = new JsonObject();
        JsonArray subjectList = new JsonArray();

        // 이제 user_id와 role 둘 다 받아옵니다.
        String userId = request.getParameter("user_id");
        String role   = request.getParameter("role");

        // 1. 오늘 요일(“월”~“일”)
        DayOfWeek d       = LocalDate.now().getDayOfWeek();
        String   dayOfWeek= getDayOfWeekKor(d);

        // 2. 현재 교시 (학교 시간표에 맞게 조정)
        int period = getCurrentPeriod();

        if (userId == null || role == null) {
            json.addProperty("result", "fail");
            json.addProperty("message", "user_id 또는 role 누락");
            out.print(json.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            PreparedStatement pstmt;
            if ("professor".equals(role)) {
                // 교수는 자신이 담당한 과목
                pstmt = conn.prepareStatement(
                        "SELECT subject_id, name, day_of_week, period, professor_id\n" +
                                "  FROM subjects\n" +
                                " WHERE professor_id = ?\n" +
                                "   AND day_of_week = ?\n" +
                                "   AND period = ?");
                pstmt.setString(1, userId);
                pstmt.setString(2, dayOfWeek);
                pstmt.setInt(3, period);
            } else {
                // 학생은 수강 중인 과목
                pstmt = conn.prepareStatement(
                        "SELECT subject_id, name, day_of_week, period, professor_id " +
                                "FROM subjects");
//                pstmt.setString(1, userId);
//                pstmt.setString(2, dayOfWeek);
//                pstmt.setInt(3, period);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JsonObject subj = new JsonObject();
                    subj.addProperty("subject_id",   rs.getInt("subject_id"));
                    subj.addProperty("name",         rs.getString("name"));
                    subj.addProperty("day_of_week",  rs.getString("day_of_week"));
                    subj.addProperty("period",       rs.getInt("period"));
                    subj.addProperty("professor_id", rs.getString("professor_id"));
                    subjectList.add(subj);
                }
            }

            json.addProperty("result", "success");
            json.add("subjects", subjectList);

        } catch (Exception e) {
            json.addProperty("result", "error");
            json.addProperty("message", e.getMessage());
            e.printStackTrace();
        }

        out.print(json.toString());
    }

    private String getDayOfWeekKor(DayOfWeek day) {
        switch (day) {
            case MONDAY:    return "월";
            case TUESDAY:   return "화";
            case WEDNESDAY: return "수";
            case THURSDAY:  return "목";
            case FRIDAY:    return "금";
            case SATURDAY:  return "토";
            case SUNDAY:    return "일";
        }
        return "";
    }

    private int getCurrentPeriod() {
        LocalTime now = LocalTime.now();
        if (now.isAfter(LocalTime.of(9,  0)) && now.isBefore(LocalTime.of(9,  50))) return 1;
        if (now.isAfter(LocalTime.of(10, 0)) && now.isBefore(LocalTime.of(10, 50))) return 2;
        if (now.isAfter(LocalTime.of(11, 0)) && now.isBefore(LocalTime.of(11, 50))) return 3;
        if (now.isAfter(LocalTime.of(12, 0)) && now.isBefore(LocalTime.of(12, 50))) return 4;
        if (now.isAfter(LocalTime.of(13, 0)) && now.isBefore(LocalTime.of(13, 50))) return 5;
        if (now.isAfter(LocalTime.of(14, 0)) && now.isBefore(LocalTime.of(14, 50))) return 6;
        if (now.isAfter(LocalTime.of(15, 0)) && now.isBefore(LocalTime.of(15, 50))) return 7;
        if (now.isAfter(LocalTime.of(16, 0)) && now.isBefore(LocalTime.of(16, 50))) return 8;
        if (now.isAfter(LocalTime.of(17, 0)) && now.isBefore(LocalTime.of(17, 50))) return 9;
        return 0;
    }
}
