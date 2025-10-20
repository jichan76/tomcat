package com.example.attendance.servlet;

import com.example.attendance.repository.InMemoryDB;
import com.google.gson.JsonObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@WebServlet("/startAttendance")
public class StartAttendanceServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(StartAttendanceServlet.class.getName());

    private static final Semaphore GUARD = new Semaphore(2, true);
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS    = 8000;

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    private void setJson(HttpServletResponse resp){
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        setJson(resp);
        boolean ok = false;
        PrintWriter out = resp.getWriter();

        try {
            ok = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                resp.setStatus(503);
                out.write("{\"result\":\"busy\"}");
                return;
            }

            req.setCharacterEncoding("UTF-8");

            String subjectIdStr = req.getParameter("subjectId");
            String bssid        = req.getParameter("bssid");
            String weekStr      = req.getParameter("week");

            if (subjectIdStr == null || weekStr == null) {
                resp.setStatus(400);
                out.write("{\"result\":\"error\",\"message\":\"subjectId/week 누락\"}");
                return;
            }

            int subjectId = Integer.parseInt(subjectIdStr);
            int week = Integer.parseInt(weekStr);

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);

                // 🔹 기존 결석 → 지각 자동 처리 (학생 개별 출석 시점 예시)
                // ※ 실제 구현에서는 학생 ID, open_id가 들어와야 함
                String studentId = req.getParameter("studentId");
                if (studentId != null && !studentId.isEmpty()) {
                    String check = "SELECT status FROM attendance_records WHERE subject_id=? AND student_id=? AND week_number=?";
                    try (PreparedStatement ps = conn.prepareStatement(check)) {
                        ps.setInt(1, subjectId);
                        ps.setString(2, studentId);
                        ps.setInt(3, week);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                String cur = rs.getString("status");
                                if ("결석".equals(cur)) {
                                    String upd = "UPDATE attendance_records SET status='지각', last_modified_at=SYSTIMESTAMP WHERE subject_id=? AND student_id=? AND week_number=?";
                                    try (PreparedStatement psu = conn.prepareStatement(upd)) {
                                        psu.setInt(1, subjectId);
                                        psu.setString(2, studentId);
                                        psu.setInt(3, week);
                                        psu.executeUpdate();
                                        System.out.println("[StartAttendance] 결석→지각 자동 변경: " + studentId);
                                    }
                                }
                            }
                        }
                    }
                }

                // 🔹 출석 세션 기록 (기존 로직)
                final String INSERT_LOG =
                        "INSERT INTO attendance_open_log (open_id, subject_id, bssid, open_datetime, close_datetime, week_number) " +
                                "VALUES (attendance_open_log_seq.NEXTVAL, ?, ?, SYSTIMESTAMP, NULL, ?)";
                try (PreparedStatement ps = conn.prepareStatement(INSERT_LOG)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    ps.setString(2, bssid);
                    ps.setInt(3, week);
                    ps.executeUpdate();
                }
                // ✅ [추가] 교수 BSSID를 subjects 테이블에도 저장
                final String UPDATE_SUBJECT_BSSID =
                        "UPDATE subjects SET professor_bssid = ? WHERE subject_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(UPDATE_SUBJECT_BSSID)) {
                    ps.setString(1, bssid);
                    ps.setInt(2, subjectId);
                    ps.executeUpdate();
                    logger.info("[StartAttendance] subjects 테이블에 BSSID 저장 완료 (" + bssid + ")");
                }

                conn.commit();
                JsonObject result = new JsonObject();
                result.addProperty("result", "started");
                result.addProperty("subjectId", subjectId);
                result.addProperty("week", week);
                out.write(result.toString());
            }

        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(500);
            out.write("{\"result\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (ok) GUARD.release();
        }
    }
}
