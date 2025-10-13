package com.example.attendance.servlet;

import com.google.gson.JsonObject;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet("/subjectInfo")
public class SubjectInfoServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(410); // Gone
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
        JsonObject o = new JsonObject();
        o.addProperty("result", "gone");
        o.addProperty("message", "subjectInfo는 더 이상 사용하지 않습니다.");
        try (PrintWriter out = resp.getWriter()) { out.print(o.toString()); }
    }
}
