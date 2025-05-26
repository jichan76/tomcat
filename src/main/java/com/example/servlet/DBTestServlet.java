package com.example.servlet;

import com.example.servlet.util.OracleUtil;
import com.example.servlet.util.FileUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.sql.*;

@WebServlet("/dbtest")
public class DBTestServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json; charset=UTF-8");

        try (Connection conn = OracleUtil.getConnection()) {

            // ✅ 쿼리 파일의 실제 배포 경로로부터 읽기
            String sqlPath ="/opt/tomcat9/queries/dbtest.sql";
            String sql = FileUtil.readSql(sqlPath);

            PreparedStatement pstmt = conn.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery();

            JsonArray jsonArray = new JsonArray();

            while (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", rs.getString("id"));
                obj.addProperty("name", rs.getString("name"));
                jsonArray.add(obj);
            }

            PrintWriter out = response.getWriter();
            out.print(jsonArray.toString());
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();

            JsonObject error = new JsonObject();
            error.addProperty("error", "DB 연결 오류: " + e.getMessage());

            response.getWriter().print(error.toString());
        }
    }
}