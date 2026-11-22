package com.aijob.backend.interview.voice;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aijob.backend.common.CommonResult;
import com.aijob.backend.common.CurrentUserResolver;
import com.aijob.backend.common.Data;
import com.aijob.backend.common.Responses;
import com.aijob.backend.common.BusinessException;
import com.aijob.backend.interview.InterviewMapper;

/** 语音面试对外接口：V1 能力探测、V2 转写、V3 查询、V4 放弃、V5 合成、V7 隐私同意。 */
@RestController
@RequestMapping("/api/v1/interviews")
public class VoiceInterviewController {

    private final CurrentUserResolver users;
    private final InterviewMapper interviews;
    private final VoiceCapabilityService capability;
    private final VoiceTranscriptionService transcriptions;
    private final QuestionSpeechService speech;

    public VoiceInterviewController(CurrentUserResolver users, InterviewMapper interviews,
                                    VoiceCapabilityService capability, VoiceTranscriptionService transcriptions,
                                    QuestionSpeechService speech) {
        this.users = users;
        this.interviews = interviews;
        this.capability = capability;
        this.transcriptions = transcriptions;
        this.speech = speech;
    }

    @GetMapping("/{interviewId}/voice-capability")
    public CommonResult<Map<String, Object>> capability(@PathVariable String interviewId) {
        return Responses.ok(capability.capability(users.resolveCurrentUserId(), Data.uuid(interviewId, "interviewId")));
    }

    @PostMapping(value = "/{interviewId}/voice-transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<Map<String, Object>> transcribe(@PathVariable String interviewId,
                                                        @RequestPart("audio") MultipartFile audio,
                                                        @RequestParam String questionMessageId,
                                                        @RequestParam int expectedVersion,
                                                        @RequestParam int durationMs,
                                                        @RequestParam(required = false) String mimeType) {
        String userId = users.resolveCurrentUserId();
        String id = Data.uuid(interviewId, "interviewId");
        return Responses.ok(transcriptions.transcribe(userId, id, audio, Data.uuid(questionMessageId, "questionMessageId"),
                expectedVersion, durationMs, mimeType));
    }

    @GetMapping("/{interviewId}/voice-transcriptions")
    public CommonResult<Map<String, Object>> latest(@PathVariable String interviewId,
                                                    @RequestParam String questionMessageId) {
        return Responses.ok(transcriptions.latest(users.resolveCurrentUserId(), Data.uuid(interviewId, "interviewId"),
                Data.uuid(questionMessageId, "questionMessageId")));
    }

    @PostMapping("/{interviewId}/voice-transcriptions/{transcriptionId}/discard")
    public CommonResult<Boolean> discard(@PathVariable String interviewId, @PathVariable String transcriptionId) {
        return Responses.ok(transcriptions.discard(users.resolveCurrentUserId(), Data.uuid(interviewId, "interviewId"),
                Data.uuid(transcriptionId, "transcriptionId")));
    }

    @PostMapping("/{interviewId}/messages/{messageId}/speech")
    public ResponseEntity<byte[]> speech(@PathVariable String interviewId, @PathVariable String messageId,
                                         @RequestHeader(value = "X-Voice-Id", required = false) String voice) {
        String userId = users.resolveCurrentUserId();
        String id = Data.uuid(interviewId, "interviewId");
        String mid = Data.uuid(messageId, "messageId");
        Map<String, Object> message = interviews.message(mid, id, userId);
        if (message == null || !"ASSISTANT".equals(String.valueOf(message.get("role")))) {
            throw BusinessException.notFound();
        }
        QuestionSpeechService.AudioResult result = speech.synthesize(userId, id, mid,
                String.valueOf(message.get("content")), voice);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Speech-Voice", result.voice())
                .header("X-Speech-Chars", String.valueOf(result.chars()))
                .body(result.audio());
    }

    @PostMapping("/{interviewId}/voice-consent")
    public CommonResult<Map<String, Object>> consent(@PathVariable String interviewId, @RequestBody Map<String, Object> body) {
        String userId = users.resolveCurrentUserId();
        Data.uuid(interviewId, "interviewId");
        if (interviews.session(Data.uuid(interviewId, "interviewId"), userId) == null) {
            throw BusinessException.notFound();
        }
        String policyVersion = Data.text(body, "policyVersion", true, 32);
        boolean agreed = Data.bool(body, "agreed", true);
        return Responses.ok(capability.agree(userId, policyVersion, agreed));
    }
}