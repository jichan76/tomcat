package com.example.attendance.repository;

import com.example.attendance.model.Student;
import com.example.attendance.model.Subject;

import java.util.*;

public class InMemoryDB {
    // 과목ID → 과목정보
    public static final Map<Integer, Subject> SUBJECTS = new HashMap<>();
    // 과목ID → 학생 목록
    public static final Map<Integer, List<Student>> STUDENTS = new HashMap<>();
    // 과목ID → 출석한 학생ID
    public static final Map<Integer, Set<Integer>> ATTENDANCE = new HashMap<>();
}
