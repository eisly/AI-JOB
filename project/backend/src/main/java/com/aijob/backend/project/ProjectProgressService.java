package com.aijob.backend.project;

import org.springframework.stereotype.Service;

@Service
public class ProjectProgressService {

    public Result calculate(long completed, long total) {
        if (total == 0) {
            return new Result(0, "ACTIVE");
        }
        int progress = (int) (completed * 100 / total);
        return new Result(progress, completed == total ? "COMPLETED" : "ACTIVE");
    }

    public record Result(int progress, String status) {
    }
}