package com.example.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

import com.google.gson.JsonObject;

@WebServlet("/LoginServlet")
public class LoginServlet extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out = response.getWriter();
        JsonObject jsonResponse = new JsonObject();

        String userId = request.getParameter("id");
        String password = request.getParameter("password");

        if (userId == null || password == null) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "Missing id or password");
            out.print(jsonResponse.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT * FROM users WHERE user_id = ? AND password = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                pstmt.setString(2, password);

                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    jsonResponse.addProperty("result", "success");
                    jsonResponse.addProperty("role", rs.getString("role"));
                    jsonResponse.addProperty("name", rs.getString("name"));
                } else {
                    jsonResponse.addProperty("result", "fail");
                }
            }
        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", e.getMessage());
            e.printStackTrace();
        }
        out.print(jsonResponse.toString());
    }
}
