package com.example.attendance.servlet;

import com.example.attendance.model.Subject;
import com.example.attendance.repository.InMemoryDB;
import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;

@WebServlet("/subjectInfo")
public class SubjectInfoServlet extends HttpServlet {
    private Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String subjectIdStr = req.getParameter("subjectId");
        int subjectId = subjectIdStr == null ? 0 : Integer.parseInt(subjectIdStr);

        Subject subject = InMemoryDB.SUBJECTS.get(subjectId);

        resp.setContentType("application/json; charset=UTF-8");
        if (subject != null)
            resp.getWriter().write(gson.toJson(subject));
        else
            resp.getWriter().write("{}");
    }
}
