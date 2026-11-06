package com.aijob.backend.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ProjectProgressServiceTest {

    private final ProjectProgressService service = new ProjectProgressService();

    @Test
    void returnsZeroAndActiveWhenThereAreNoTasks() {
        ProjectProgressService.Result result = service.calculate(0, 0);

        assertEquals(0, result.progress());
        assertEquals("ACTIVE", result.status());
    }

    @Test
    void floorsFractionalProgress() {
        ProjectProgressService.Result result = service.calculate(2, 3);

        assertEquals(66, result.progress());
        assertEquals("ACTIVE", result.status());
    }

    @Test
    void completesProjectWhenAllTasksAreCompleted() {
        ProjectProgressService.Result result = service.calculate(3, 3);

        assertEquals(100, result.progress());
        assertEquals("COMPLETED", result.status());
    }
}