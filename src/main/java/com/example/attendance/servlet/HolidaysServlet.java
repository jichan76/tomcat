package com.example.attendance.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * /Holidays?year=2025
 *
 * HOLIDAY 테이블의 휴일 목록을 JSON 배열로 반환
 * ex)
 * [
 *   {"date":"2025-10-03","name":"개천절"},
 *   {"date":"2025-10-05","name":"추석 연휴"},
 *   {"date":"2025-10-06","name":"추석"},
 *   {"date":"2025-10-08","name":"추석 대체공휴일(임시공휴일)"},
 *   {"date":"2025-10-09","name":"한글날"}
 * ]
 */

@WebServlet("/Holidays")
public class HolidaysServlet extends HttpServlet {
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN"; String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String year = req.getParameter("year"); if (year == null) year = "2025";

        String sql =
                "SELECT TO_CHAR(HDATE,'YYYY-MM-DD') AS d, HNAME " +
                        "FROM HOLIDAY WHERE EXTRACT(YEAR FROM HDATE) = ? ORDER BY HDATE";

        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, Integer.parseInt(year));
            try (ResultSet rs = ps.executeQuery()) {
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                while (rs.next()) {
                    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                    o.addProperty("date", rs.getString("d"));
                    o.addProperty("name", rs.getString("HNAME"));
                    arr.add(o);
                }
                resp.getWriter().write(arr.toString());
            }
        } catch (Exception e) {
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\""+e.getMessage()+"\"}");
        }
    }
}

