package com.example.attendance.servlet;

import com.google.gson.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.Properties;

@WebServlet("/StudentAttendanceStatusServlet")
public class StudentAttendanceStatusServlet extends HttpServlet {

    private static final Semaphore GUARD = new Semaphore(16, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    private void setJson(HttpServletResponse resp) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        setJson(resp);
        boolean acquired = false;
        PrintWriter out = resp.getWriter();

        try {
            acquired = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                resp.setStatus(503);
                out.write("{\"result\":\"busy\"}");
                return;
            }

            String studentId = req.getParameter("studentId");
            String subjectId = req.getParameter("subjectId");

            if (studentId == null || subjectId == null) {
                resp.setStatus(400);
                out.write("{\"result\":\"error\",\"message\":\"Missing studentId or subjectId\"}");
                return;
            }

            JsonArray weeks = new JsonArray();
            JsonObject summary = new JsonObject();
            int present = 0, late = 0, absent = 0;

            try (Connection conn = getConnection()) {
                // ✅ 주차별 출석상태 조회
                String sql = "SELECT week_number, status FROM attendance_records " +
                        "WHERE student_id = ? AND subject_id = ? ORDER BY week_number";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, studentId);
                    ps.setInt(2, Integer.parseInt(subjectId));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject w = new JsonObject();
                            int week = rs.getInt("week_number");
                            String status = rs.getString("status");
                            w.addProperty("week", week);
                            w.addProperty("status", status);
                            weeks.add(w);

                            if ("출석".equals(status)) present++;
                            else if ("지각_조퇴".equals(status) || "지각".equals(status) || "조퇴".equals(status)) late++;
                            else if ("결석".equals(status)) absent++;
                        }
                    }
                }
            }

            summary.addProperty("출석", present);
            summary.addProperty("지각_조퇴", late);
            summary.addProperty("결석", absent);

            JsonObject root = new JsonObject();
            root.addProperty("result", "success");
            root.add("weeks", weeks);
            root.add("summary", summary);

            out.write(root.toString());

        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("result", "error");
            err.addProperty("message", e.getMessage());
            out.write(err.toString());
        } finally {
            if (acquired) GUARD.release();
            out.flush();
            resp.flushBuffer();
        }
    }
}
