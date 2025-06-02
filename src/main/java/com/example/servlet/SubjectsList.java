package com.example.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;

@WebServlet("/SubjectsList")
public class SubjectsList extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

//    @Override
//    protected void doGet(HttpServletRequest request, HttpServletResponse response)
//            throws ServletException, IOException {
//        request.setCharacterEncoding("UTF-8");
//        response.setContentType("application/json; charset=UTF-8");
//        PrintWriter out = response.getWriter();
//
//        HttpSession session = request.getSession(false);
//        if (session == null || session.getAttribute("userId") == null) {
//            JsonObject err = new JsonObject();
//            err.addProperty("result", "error");
//            err.addProperty("message", "로그인 세션이 없습니다.");
//            out.print(err.toString());
//            return;
//        }
//        String userId = (String) session.getAttribute("userId");
//        String role   = (String) session.getAttribute("role");  // "student" or "professor"
//
//        // 3) 학생/교수별 SQL 분기
//        String sql;
//        if ("student".equals(role)) {
//            sql = "SELECT s.subject_id, s.name, s.professor_id " +
//                    "FROM subjects s " +
//                    "  JOIN enrollments e ON s.subject_id = e.subject_id " +
//                    "WHERE e.student_id = ?";
//        } else {
//            sql = "SELECT subject_id, name, professor_id " +
//                    "FROM subjects " +
//                    "WHERE professor_id = ?";
//        }
//
//        JsonArray arr = new JsonArray();
//        try (Connection conn = getConnection();
//             PreparedStatement ps = conn.prepareStatement(sql)) {
//
//            ps.setString(1, userId);
//            try (ResultSet rs = ps.executeQuery()) {
//                while (rs.next()) {
//                    JsonObject subj = new JsonObject();
//                    subj.addProperty("subjectId",   rs.getInt("subject_id"));
//                    subj.addProperty("name",        rs.getString("name"));
//                    subj.addProperty("professorId", rs.getString("professor_id"));
//                    arr.add(subj);
//                }
//            }
//        } catch (Exception e) {
//            JsonObject err = new JsonObject();
//            err.addProperty("result", "error");
//            err.addProperty("message", e.getMessage());
//            out.print(err.toString());
//            return;
//        }
//
//        // 4) 결과 전송
//        JsonObject result = new JsonObject();
//        result.addProperty("result", "success");
//        result.add("subjects", arr);
//        out.print(result.toString());
//    }
//}
// 하드 코딩 테스트용
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out = response.getWriter();

        // 1) studentId 파라미터 우선
        String studentId = request.getParameter("studentId");
        String role       = null;

        if (studentId != null && !studentId.isEmpty()) {
            // 테스트용 하드코딩 모드: role은 "student"로 고정
            role = "student";
        } else {
            // 기존 세션 체크 로직
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute("userId") == null) {
                JsonObject err = new JsonObject();
                err.addProperty("result", "error");
                err.addProperty("message", "로그인 세션이 없습니다.");
                out.print(err.toString());
                return;
            }
            studentId = (String) session.getAttribute("userId");
            role      = (String) session.getAttribute("role");
        }

        // 2) SQL 분기 (student / professor)
        String sql;
        if ("student".equals(role)) {
            sql = "SELECT s.name" +
                    "FROM subjects s " +
                    "  JOIN enrollments e ON s.subject_id = e.subject_id " +
                    "WHERE e.student_id = ?";
        } else {
            sql = "SELECT name" +
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

        // 3) 결과 전송
        JsonObject result = new JsonObject();
        result.addProperty("result",   "success");
        result.add("subjects", arr);
        out.print(result.toString());
    }
}