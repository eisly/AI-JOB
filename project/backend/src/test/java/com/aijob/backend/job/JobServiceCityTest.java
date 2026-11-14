package com.aijob.backend.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JobServiceCityTest {
    @Test
    void extractsExplicitWorkCityWithoutGuessingFromUnrelatedText() {
        assertEquals("440100", JobService.cityCodeFromJd("工作地点：广州\n要求熟悉 Spring Boot 和 FastAPI"));
        assertNull(JobService.cityCodeFromJd("公司总部位于广州，工作地点待定"));
        assertNull(JobService.cityCodeFromJd("工作地点：广州\n办公地点：深圳"));
        assertNull(JobService.cityCodeFromJd("工作地点：广州、深圳"));
    }
}
