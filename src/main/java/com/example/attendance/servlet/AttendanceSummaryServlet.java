package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebServlet("/AttendanceSummaryServlet")
public class AttendanceSummaryServlet extends HttpServlet {

    // ───────────────────── 설정값 ─────────────────────
    private static final int MAX_CONCURRENT = 16;
    private static final int ACQUIRE_TIMEOUT_MS = 300;
    private static final int SQL_TIMEOUT_SEC = 8;
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS = 8000;
    private static final Semaphore GUARD = new Semaphore(MAX_CONCURRENT, true);
    private static final Logger logger = Logger.getLogger(AttendanceSummaryServlet.class.getName());

    // ───────────────────── DB 연결 ─────────────────────
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT", String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout", String.valueOf(DB_READ_TIMEOUT_MS));
        Class.forName("oracle.jdbc.driver.OracleDriver");
        Connection conn = DriverManager.getConnection(url, p);

        try (Statement st = conn.createStatement()) {
            st.execute("ALTER SESSION DISABLE PARALLEL DML");
            st.execute("ALTER SESSION DISABLE PARALLEL QUERY");
            st.execute("ALTER SESSION SET parallel_degree_policy = MANUAL");
            st.execute("ALTER SESSION SET parallel_degree_limit = 1");
        } catch (SQLException ignore) {
        }

        return conn;
    }

    private void setJson(HttpServletResponse resp) {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
    }

    // ───────────────────── GET 요청 처리 ─────────────────────
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        setJson(resp);
        boolean acquired = false;
        PrintWriter out = resp.getWriter();

        try {
            // 1️⃣ 세마포어 획득
            acquired = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                resp.setStatus(503);
                out.write("{\"result\":\"busy\"}");
                out.flush();
                logger.warning("AttendanceSummaryServlet: DB 연결 과부하로 요청 거부됨");
                return;
            }

            req.setCharacterEncoding("UTF-8");

            // 2️⃣ 파라미터 확인
            String subjectIdStr = trim(req.getParameter("subjectId"));
            String weekStr = trim(req.getParameter("week"));

            logger.info("요청 수신 → subjectId=" + subjectIdStr + ", week=" + weekStr);

            if (subjectIdStr == null) {
                resp.setStatus(400);
                out.write("{\"result\":\"error\",\"message\":\"Missing subjectId\"}");
                out.flush();
                return;
            }

            int subjectId = Integer.parseInt(subjectIdStr);

            // ────────────────────────────────────────────────
            // ✅ 학생 모드: week 파라미터 없음 → 전체 주차 날짜 반환
            // ────────────────────────────────────────────────
            if (weekStr == null) {
                LocalDate semesterStart = LocalDate.of(2025, 8, 18); // 학기 시작일
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM/dd");
                JsonArray weekArr = new JsonArray();

                try (Connection conn = getConnection()) {
                    String sql = "SELECT WEEK_NUMBER, NVL(MAKEUP_DATE, ORIGINAL_DATE) AS FINAL_DATE " +
                            "FROM CLASS_SCHEDULE WHERE SUBJECT_ID = ? ORDER BY WEEK_NUMBER";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                        ps.setInt(1, subjectId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                int week = rs.getInt("WEEK_NUMBER");
                                Date sqlDate = rs.getDate("FINAL_DATE");
                                String dateStr = (sqlDate != null)
                                        ? sqlDate.toLocalDate().format(fmt)
                                        : semesterStart.plusWeeks(week - 1).format(fmt);

                                JsonObject w = new JsonObject();
                                w.addProperty("week", week);
                                w.addProperty("date", dateStr);
                                weekArr.add(w);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "주차 날짜 조회 실패 → 기본 날짜로 대체", e);
                    for (int i = 1; i <= 16; i++) {
                        JsonObject w = new JsonObject();
                        w.addProperty("week", i);
                        w.addProperty("date", semesterStart.plusWeeks(i - 1).format(fmt));
                        weekArr.add(w);
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("result", "success");
                result.add("weeks", weekArr);

                out.print(result.toString());
                out.flush();
                return;
            }

            // ────────────────────────────────────────────────
            // ✅ 교수 모드: 특정 주차 통계 조회 (수정된 부분)
            // ────────────────────────────────────────────────
            int week = Integer.parseInt(weekStr);
            int present = 0, late = 0, absent = 0, enrolled = 0, unchecked = 0;

            try (Connection conn = getConnection()) {
                logger.info("DB 연결 성공 → subjectId=" + subjectId + ", week=" + week);

                // (1) 상태별 카운트
                String sql = "SELECT STATUS, COUNT(*) CNT " +
                        "FROM ATTENDANCE_RECORDS " +
                        "WHERE SUBJECT_ID = ? AND WEEK_NUMBER = ? " +
                        "GROUP BY STATUS";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    ps.setInt(2, week);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String status = rs.getString("STATUS");
                            int cnt = rs.getInt("CNT");
                            if ("출석".equals(status)) present = cnt;
                            else if ("지각_조퇴".equals(status)) late = cnt;
                            else if ("결석".equals(status)) absent = cnt;
                        }
                    }
                }

                // (2) 전체 수강 인원
                String totalSql = "SELECT COUNT(*) FROM ENROLLMENTS WHERE SUBJECT_ID = ?";
                try (PreparedStatement ps = conn.prepareStatement(totalSql)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) enrolled = rs.getInt(1);
                    }
                }

                // (3) ✅ 미체크 인원 계산
                // ATTENDANCE_RECORDS에 아직 해당 주차 출석기록이 없는 학생 수 계산
                String uncheckedSql =
                        "SELECT COUNT(*) FROM ENROLLMENTS e " +
                                "WHERE e.SUBJECT_ID = ? " +
                                "AND NOT EXISTS (" +
                                "  SELECT 1 FROM ATTENDANCE_RECORDS a " +
                                "  WHERE a.SUBJECT_ID = e.SUBJECT_ID " +
                                "  AND a.STUDENT_ID = e.STUDENT_ID " +
                                "  AND a.WEEK_NUMBER = ?" +
                                ")";
                try (PreparedStatement ps = conn.prepareStatement(uncheckedSql)) {
                    ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                    ps.setInt(1, subjectId);
                    ps.setInt(2, week);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) unchecked = rs.getInt(1);
                    }
                }

                // 미체크 수를 enrolled 필드로 내려줌 (앱에서 '미체크'로 표시)
                enrolled = unchecked;
            }

            // (4) 결과 JSON 작성
            JsonObject root = new JsonObject();
            root.addProperty("result", "success");
            root.addProperty("present", present);
            root.addProperty("late", late);
            root.addProperty("absent", absent);
            root.addProperty("enrolled", enrolled); // ✅ 미체크 인원 수로 변경

            out.print(root.toString());
            out.flush();

            logger.info("출결 요약 결과 → 출석=" + present + ", 지각=" + late + ", 결석=" + absent + ", 미체크=" + enrolled);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "AttendanceSummaryServlet 내부 오류 발생", e);
            resp.setStatus(500);
            JsonObject err = new JsonObject();
            err.addProperty("result", "error");
            err.addProperty("message", e.toString());
            out.print(err.toString());
            out.flush();
        } finally {
            if (acquired) GUARD.release();
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        setJson(resp);
        resp.setStatus(405);
        try (PrintWriter out = resp.getWriter()) {
            out.write("{\"result\":\"fail\",\"message\":\"GET만 지원합니다.\"}");
            out.flush();
        }
    }
}
