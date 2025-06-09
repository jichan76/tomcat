package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

@WebServlet("/attendanceStatus")
public class AttendanceStatusServlet extends HttpServlet {

    // 오라클 DB 연결 메서드
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");

        String studentId = request.getParameter("studentId");
        String subjectId = request.getParameter("subjectId");
        // 주차별 출결상태 쿼리
        String weekQuery =
                "SELECT week_number, status " +
                        "FROM ( " +
                        "  SELECT " +
                        "    FLOOR((CAST(attendance_datetime AS DATE) - si.start_date)/7)+1 AS week_number, " +
                        "    ar.status, " +
                        "    ROW_NUMBER() OVER (PARTITION BY FLOOR((CAST(attendance_datetime AS DATE) - si.start_date)/7)+1 " +
                        "      ORDER BY ar.attendance_datetime DESC) AS rn " +
                        "  FROM attendance_records ar, semester_info si " +
                        "  WHERE ar.student_id = ? AND ar.subject_id = ? " +
                        ") " +
                        "WHERE rn = 1 AND week_number BETWEEN 1 AND 16";

        // 요약 쿼리
        String sumQuery =
                "SELECT status, COUNT(*) cnt " +
                        "FROM ( " +
                        "  SELECT " +
                        "    FLOOR((CAST(attendance_datetime AS DATE) - si.start_date)/7)+1 AS week_number, " +
                        "    ar.status, " +
                        "    ROW_NUMBER() OVER (PARTITION BY FLOOR((CAST(attendance_datetime AS DATE) - si.start_date)/7)+1 " +
                        "      ORDER BY ar.attendance_datetime DESC) AS rn " +
                        "  FROM attendance_records ar, semester_info si " +
                        "  WHERE ar.student_id = ? AND ar.subject_id = ? " +
                        ") " +
                        "WHERE rn = 1 AND week_number BETWEEN 1 AND 16 " +
                        "GROUP BY status";

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        String[] weekStatus = new String[16]; // 1~16주차, null로 초기화
        Map<String, Integer> summary = new HashMap<>();
        summary.put("출석", 0);
        summary.put("지각_조퇴", 0);
        summary.put("결석", 0);

        try {
            conn = getConnection();

            // (1) 주차별 상태
            pstmt = conn.prepareStatement(weekQuery);
            pstmt.setString(1, studentId);
            pstmt.setString(2, subjectId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                int week = rs.getInt("week_number");
                String status = rs.getString("status");
                if (week >= 1 && week <= 16) {
                    weekStatus[week - 1] = status;
                }
            }
            rs.close();
            pstmt.close();

            // (2) 합계
            pstmt = conn.prepareStatement(sumQuery);
            pstmt.setString(1, studentId);
            pstmt.setString(2, subjectId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                String status = rs.getString("status");
                int cnt = rs.getInt("cnt");
                if (summary.containsKey(status)) summary.put(status, cnt);
            }
            rs.close();
            pstmt.close();

            // (3) JSON 응답 구성
            JsonObject root = new JsonObject();

            // weeks
            JsonArray weeks = new JsonArray();
            for (int i = 0; i < 16; i++) {
                JsonObject wk = new JsonObject();
                wk.addProperty("week", i + 1);
                if (weekStatus[i] != null) {
                    wk.addProperty("status", weekStatus[i]);
                } else {
                    wk.add("status", null);
                }
                weeks.add(wk);
            }
            root.add("weeks", weeks);

            // summary
            JsonObject summaryObj = new JsonObject();
            summaryObj.addProperty("출석", summary.get("출석"));
            summaryObj.addProperty("지각_조퇴", summary.get("지각_조퇴"));
            summaryObj.addProperty("결석", summary.get("결석"));
            root.add("summary", summaryObj);

            PrintWriter out = response.getWriter();
            out.print(new Gson().toJson(root));
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter().write("{\"error\":\"서버 오류\"}");
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (pstmt != null) pstmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }
}
