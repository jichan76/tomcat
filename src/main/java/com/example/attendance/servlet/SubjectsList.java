package com.example.attendance.servlet;

import com.google.gson.JsonArray;
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

@WebServlet("/SubjectsList")
public class SubjectsList extends HttpServlet {

    private static final int DB_CONNECT_TIMEOUT_MS = 8_000;
    private static final int DB_READ_TIMEOUT_MS    = 8_000;
    private static final int SQL_TIMEOUT_SEC       = 8;
    private static final int MAX_CONCURRENT        = 24;
    private static final int ACQUIRE_TIMEOUT_MS    = 300;

    private static final Semaphore GUARD = new Semaphore(MAX_CONCURRENT, true);

    private static final String JDBC_URL = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
    private static final String DB_USER  = "ADMIN";
    private static final String DB_PASS  = "NewPassword123!";

    static {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            DriverManager.setLoginTimeout(DB_CONNECT_TIMEOUT_MS / 1000);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Connection getConnection() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", DB_USER);
        p.setProperty("password", DB_PASS);
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT",    String.valueOf(DB_READ_TIMEOUT_MS));
        p.setProperty("oracle.jdbc.ReadTimeout",    String.valueOf(DB_READ_TIMEOUT_MS));
        return DriverManager.getConnection(JDBC_URL, p);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean acquired = false;
        try {
            acquired = GUARD.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                response.setStatus(503);
                response.getWriter().write("{\"result\":\"busy\"}");
                return;
            }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");

            String studentId = request.getParameter("studentId");
            String role      = request.getParameter("role");

            // 세션 보정
            if ((studentId == null || studentId.isEmpty()) || (role == null || role.isEmpty())) {
                HttpSession session = request.getSession(false);
                if (session == null || session.getAttribute("userId") == null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("result", "error");
                    err.addProperty("message", "로그인 세션이 없습니다.");
                    response.getWriter().print(err.toString());
                    return;
                }
                if (studentId == null || studentId.isEmpty())
                    studentId = (String) session.getAttribute("userId");
                if (role == null || role.isEmpty())
                    role = (String) session.getAttribute("role");
            }

            // ✅ end_period까지 SELECT (학생/교수 공통)  // NEW
            final String sqlStudent =
                    "SELECT s.subject_id, s.name, s.professor_id, s.day_of_week, s.period, s.end_period " +
                            "  FROM subjects s " +
                            "  JOIN enrollments e ON s.subject_id = e.subject_id " +
                            " WHERE e.student_id = ?";

            final String sqlProfessor =
                    "SELECT subject_id, name, professor_id, day_of_week, period, end_period " +
                            "  FROM subjects " +
                            " WHERE professor_id = ?";

            String sql = "student".equalsIgnoreCase(role) ? sqlStudent : sqlProfessor;

            JsonArray arr = new JsonArray();
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(SQL_TIMEOUT_SEC);
                ps.setString(1, studentId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // --- 여기서 start/end/span 계산합니다 --- // NEW
                        int start = rs.getInt("period");
                        int end   = rs.getInt("end_period");
                        boolean endIsNull = rs.wasNull(); // getInt 바로 뒤에 호출해야 정확

                        int span = endIsNull ? 1 : Math.max(1, end - start + 1);

                        JsonObject subj = new JsonObject();
                        subj.addProperty("subjectId",   rs.getInt("subject_id"));
                        subj.addProperty("name",        rs.getString("name"));
                        subj.addProperty("professorId", rs.getString("professor_id"));

                        String dow = rs.getString("day_of_week");
                        if (dow != null) subj.addProperty("dayOfWeek", dow.trim());

                        subj.addProperty("period", start); // 시작교시  // NEW
                        subj.addProperty("span",   span);  // 연강 길이  // NEW

                        arr.add(subj);
                    }
                }
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty("result", "error");
                err.addProperty("message", "server");
                response.getWriter().print(err.toString());
                return;
            }

            JsonObject result = new JsonObject();
            result.addProperty("result", "success");
            result.add("subjects", arr);
            try (PrintWriter out = response.getWriter()) {
                out.print(result.toString());
            }

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            response.setStatus(503);
        } finally {
            if (acquired) GUARD.release();
        }
    }
}
