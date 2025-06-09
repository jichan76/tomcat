package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;

@WebServlet("/SubjectsList")
public class SubjectsList extends HttpServlet {

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
        PrintWriter out = response.getWriter();

        // 1) 파라미터에서 우선 studentId, role 읽기
        String studentId = request.getParameter("studentId");
        String role = request.getParameter("role");

        // 2) 값이 없으면 세션에서 가져오기 (세션 기반 로그인 유지 대비)
        if ((studentId == null || studentId.isEmpty()) || (role == null || role.isEmpty())) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute("userId") == null) {
                JsonObject err = new JsonObject();
                err.addProperty("result", "error");
                err.addProperty("message", "로그인 세션이 없습니다.");
                out.print(err.toString());
                return;
            }
            // 없는 것만 세션에서 채워넣기
            if (studentId == null || studentId.isEmpty())
                studentId = (String) session.getAttribute("userId");
            if (role == null || role.isEmpty())
                role = (String) session.getAttribute("role");
        }
        // 3) SQL 분기 (role이 student면 학생용 쿼리, 아니면 교수용)
        String sql;
        if ("student".equals(role)) {
            sql = "SELECT s.subject_id, s.name, s.professor_id " +
                    "FROM subjects s " +
                    "JOIN enrollments e ON s.subject_id = e.subject_id " +
                    "WHERE e.student_id = ?";
        } else {
            sql = "SELECT subject_id, name, professor_id " +
                    "FROM subjects " +
                    "WHERE professor_id = ?";
        }

        JsonArray arr = new JsonArray();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject subj = new JsonObject();
                    subj.addProperty("subjectId",   rs.getInt("subject_id"));
                    subj.addProperty("name",        rs.getString("name"));
                    subj.addProperty("professorId", rs.getString("professor_id"));
                    arr.add(subj);
                }
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("result", "error");
            err.addProperty("message", e.getMessage());
            out.print(err.toString());
            return;
        }

        // 4) 결과 전송
        JsonObject result = new JsonObject();
        result.addProperty("result",   "success");
        result.add("subjects", arr);
        out.print(result.toString());
    }
}
