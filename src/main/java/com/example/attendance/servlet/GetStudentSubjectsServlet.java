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
import java.util.logging.Logger;

/**
 * ✅ 학생이 수강 중인 모든 과목 목록 반환
 * - ENROLLMENTS + SUBJECTS 조인
 * - BSSID 조건 없이 전체 과목명 반환
 */
@WebServlet("/GetStudentSubjectsServlet")
public class GetStudentSubjectsServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(GetStudentSubjectsServlet.class.getName());

    /** ✅ DB 연결 (CurrentOpenServlet 동일 방식) */
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";

        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "8000");
        p.setProperty("oracle.net.READ_TIMEOUT", "8000");

        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json;charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        String studentId = req.getParameter("student_id");
        JsonArray arr = new JsonArray();

        try (PrintWriter out = resp.getWriter()) {
            if (studentId == null || studentId.trim().isEmpty()) {
                resp.setStatus(400);
                JsonObject err = new JsonObject();
                err.addProperty("error", "student_id parameter is required");
                out.print(err.toString());
                return;
            }

            logger.info("수강 과목 목록 요청 student_id=" + studentId);

            String sql = """
                SELECT s.subject_id, s.subject_name AS SUBJECT_TITLE
                FROM ENROLLMENTS e
                JOIN SUBJECTS s ON e.subject_id = s.subject_id
                WHERE e.student_id = ?
                ORDER BY s.subject_name
            """;

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, studentId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("SUBJECT_ID", rs.getInt("subject_id"));
                        obj.addProperty("SUBJECT_TITLE", rs.getString("SUBJECT_TITLE"));
                        arr.add(obj);
                    }
                }

                out.print(arr.toString());
                logger.info("조회된 과목 개수: " + arr.size());
            }

        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(500);
            try (PrintWriter out = resp.getWriter()) {
                JsonObject err = new JsonObject();
                err.addProperty("error", e.getMessage());
                out.print(err.toString());
            }
        }
    }
}
