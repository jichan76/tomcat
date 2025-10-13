package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.util.Properties;

@WebServlet("/SubjectStudents")
public class SubjectStudents extends HttpServlet {
    private static final String JDBC_URL = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
    private static final String DB_USER  = "ADMIN";
    private static final String DB_PASS  = "NewPassword123!";

    static {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            DriverManager.setLoginTimeout(8);
        } catch (ClassNotFoundException e) { throw new ExceptionInInitializerError(e); }
    }
    private Connection getConnection() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", DB_USER);
        p.setProperty("password", DB_PASS);
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "8000");
        p.setProperty("oracle.net.READ_TIMEOUT",    "8000");
        p.setProperty("oracle.jdbc.ReadTimeout",    "8000");
        return DriverManager.getConnection(JDBC_URL, p);
    }

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String subjectIdStr = req.getParameter("subjectId");
        String professorId  = req.getParameter("professorId"); // 선택(접근제어)

        JsonObject out = new JsonObject();
        if (subjectIdStr == null || subjectIdStr.isEmpty()) {
            out.addProperty("result", "error");
            out.addProperty("message", "subjectId required");
            resp.getWriter().print(out.toString());
            return;
        }

        String sql =
                "SELECT st.student_id, st.name " +
                        "  FROM enrollments e " +
                        "  JOIN students st ON st.student_id = e.student_id " +
                        " WHERE e.subject_id = ? " +
                        "   AND EXISTS (SELECT 1 FROM subjects s WHERE s.subject_id=e.subject_id AND s.professor_id = NVL(?, s.professor_id)) " +
                        " ORDER BY st.student_id";

        JsonArray arr = new JsonArray();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Integer.parseInt(subjectIdStr));
            if (professorId == null || professorId.isEmpty()) ps.setNull(2, Types.VARCHAR);
            else ps.setString(2, professorId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("studentId", rs.getString(1));
                    o.addProperty("name", rs.getString(2));
                    arr.add(o);
                }
            }
        } catch (Exception e) {
            out.addProperty("result", "error");
            out.addProperty("message", "server");
            resp.getWriter().print(out.toString());
            return;
        }
        out.addProperty("result", "success");
        out.add("students", arr);
        resp.getWriter().print(out.toString());
    }
}
