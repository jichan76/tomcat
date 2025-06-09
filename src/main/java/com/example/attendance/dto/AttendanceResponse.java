package com.example.attendance.dto;

import com.example.attendance.model.Student;
import java.util.List;

public class AttendanceResponse {
    private List<Student> presentStudents;
    private List<Student> absentStudents;

    public AttendanceResponse(List<Student> present, List<Student> absent) {
        this.presentStudents = present;
        this.absentStudents = absent;
    }
    public List<Student> getPresentStudents() { return presentStudents; }
    public List<Student> getAbsentStudents() { return absentStudents; }
}
