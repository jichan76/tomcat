// src/main/java/com/example/attendance/servlet/TouchWeekDateServlet.java
package com.example.attendance.servlet;

import com.google.gson.JsonObject;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;

@WebServlet("/TouchWeekDate")
public class TouchWeekDateServlet extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "8000");
        p.setProperty("oracle.jdbc.ReadTimeout", "8000");
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");

        JsonObject out = new JsonObject();
        try (PrintWriter w = resp.getWriter()) {
            String sidStr = req.getParameter("subjectId");
            String weekStr = req.getParameter("week");
            if (sidStr == null || weekStr == null) {
                resp.setStatus(400);
                out.addProperty("result", "error");
                out.addProperty("message", "missing subjectId or week");
                w.print(out.toString());
                return;
            }
            int subjectId = Integer.parseInt(sidStr.trim());
            int week = Integer.parseInt(weekStr.trim());
            if (week < 1 || week > 16) {
                resp.setStatus(400);
                out.addProperty("result", "error");
                out.addProperty("message", "week must be 1..16");
                w.print(out.toString());
                return;
            }

            int affected = 0;
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);

                // 1) 오늘(한국시간)과 예정일 계산
                java.sql.Date todayKr = null;
                java.sql.Date planned = null;

                // 오늘(Asia/Seoul)
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT CAST((SYSTIMESTAMP AT TIME ZONE 'Asia/Seoul') AS DATE) AS d FROM dual")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) todayKr = rs.getDate("d");
                    }
                }

                // 예정일(학기 시작 + 과목 요일 + 7*(week-1))
                final String SQL_PLANNED =
                        "WITH base AS ( " +
                                "  SELECT NEXT_DAY(si.start_date - 1, " +
                                "    CASE UPPER(s.day_of_week) " +
                                "      WHEN 'MON' THEN 'MONDAY' WHEN 'TUE' THEN 'TUESDAY' " +
                                "      WHEN 'WED' THEN 'WEDNESDAY' WHEN 'THU' THEN 'THURSDAY' " +
                                "      WHEN 'FRI' THEN 'FRIDAY' WHEN 'SAT' THEN 'SATURDAY' " +
                                "      WHEN 'SUN' THEN 'SUNDAY' END) AS first_class_date " +
                                "  FROM subjects s " +
                                "  JOIN ( SELECT start_date FROM semester_info " +
                                "          WHERE start_date <= CAST(SYSTIMESTAMP AS DATE) " +
                                "          ORDER BY start_date DESC FETCH FIRST 1 ROWS ONLY ) si ON 1=1 " +
                                "  WHERE s.subject_id = ? " +
                                ") SELECT first_class_date + 7 * (? - 1) AS d FROM base";
                try (PreparedStatement ps = conn.prepareStatement(SQL_PLANNED)) {
                    ps.setInt(1, subjectId);
                    ps.setInt(2, week);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) planned = rs.getDate("d");
                    }
                }

                // 2) 예정일과 오늘이 다를 때만 display_date를 "한 번만" 세팅
                //    - 이미 display_date가 있으면 건드리지 않음(고정)
                //    - 없으면 오늘 날짜로 세팅
                if (todayKr != null && planned != null && !todayKr.equals(planned)) {
                    final String SQL_TOUCH =
                            "MERGE INTO attendance_closures t " +
                                    "USING (SELECT ? AS sid, ? AS wk, ? AS dd FROM dual) s " +
                                    "ON (t.subject_id = s.sid AND t.week_number = s.wk) " +
                                    "WHEN MATCHED THEN UPDATE SET t.display_date = s.dd " +
                                    "  WHERE t.display_date IS NULL " +            // ← 이미 있으면 불변
                                    "WHEN NOT MATCHED THEN INSERT (subject_id, week_number, display_date) " +
                                    "VALUES (s.sid, s.wk, s.dd)";
                    try (PreparedStatement ps = conn.prepareStatement(SQL_TOUCH)) {
                        ps.setInt(1, subjectId);
                        ps.setInt(2, week);
                        ps.setDate(3, todayKr);
                        affected = ps.executeUpdate();
                    }
                }

                conn.commit();
            }

            out.addProperty("result", "success");
            out.addProperty("touched", affected); // 0이면 이미 고정이 있었던 것
            out.addProperty("subjectId", subjectId);
            out.addProperty("week", week);
            w.print(out.toString());
        } catch (Exception e) {
            resp.setStatus(500);
            out.addProperty("result", "error");
            out.addProperty("message", e.getMessage());
            resp.getWriter().print(out.toString());
        }
    }
}
