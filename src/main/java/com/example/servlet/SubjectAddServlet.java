package com.example.servlet;

import com.google.gson.JsonObject;

import com.example.servlet.util.OracleUtil;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;

//@WebServlet("/subjects")  // /subjects 경로로 POST 요청 받음
public class SubjectAddServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json; charset=UTF-8");

        // 1. 요청 body(JSON) 읽기
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        String body = sb.toString();

        // 2. JSON 파싱 (Gson, JsonObject)
        com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
        JsonObject json = parser.parse(body).getAsJsonObject();

        String name = json.get("name").getAsString();
        int professorId = json.get("professorId").getAsInt();
        String dayOfWeek = json.get("dayOfWeek").getAsString();
        int startPeriod = json.get("startPeriod").getAsInt();
        int endPeriod = json.get("endPeriod").getAsInt();

        // 3. DB INSERT
        try (Connection conn = OracleUtil.getConnection()) {
            String sql = "INSERT INTO subjects (name, professor_id, day_of_week, start_period, end_period) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, name);
                pstmt.setInt(2, professorId);
                pstmt.setString(3, dayOfWeek);
                pstmt.setInt(4, startPeriod);
                pstmt.setInt(5, endPeriod);

                int affected = pstmt.executeUpdate();

                // 4. 결과 JSON 반환
                JsonObject res = new JsonObject();
                res.addProperty("success", affected > 0);
                response.getWriter().print(res.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            JsonObject res = new JsonObject();
            res.addProperty("success", false);
            res.addProperty("error", e.getMessage());
            response.getWriter().print(res.toString());
        }
    }
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json; charset=UTF-8");
        String dayOfWeek = request.getParameter("dayOfWeek"); // 요일 파라미터 받기

        try (Connection conn = OracleUtil.getConnection()) {
            String sql = "SELECT * FROM subjects WHERE day_of_week = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, dayOfWeek);  // <-- 여기 중요!!
                try (ResultSet rs = pstmt.executeQuery()) {

                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                    while (rs.next()) {
                        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
                        obj.addProperty("id", rs.getInt("id"));
                        obj.addProperty("name", rs.getString("name"));
                        obj.addProperty("professorId", rs.getInt("professor_id"));
                        obj.addProperty("dayOfWeek", rs.getString("day_of_week"));
                        obj.addProperty("startPeriod", rs.getInt("start_period"));
                        obj.addProperty("endPeriod", rs.getInt("end_period"));
                        arr.add(obj);
                    }
                    response.getWriter().print(arr.toString());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.getWriter().print("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

}
