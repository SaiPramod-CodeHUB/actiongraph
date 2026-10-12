package com.saipramod.actiongraph.api;

import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.service.MeetingService;
import com.saipramod.actiongraph.stt.SpeechToText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class MeetingController {

    public record NewMeeting(@NotBlank String title, @NotNull LocalDate date, String series,
                             @NotBlank @Size(max = 200_000) String transcript) { }

    public record Review(boolean approve, String owner, LocalDate deadline, String action) { }

    private final MeetingService service;
    private final SpeechToText stt;

    public MeetingController(MeetingService service, SpeechToText stt) {
        this.service = service;
        this.stt = stt;
    }

    @PostMapping("/meetings")
    public MeetingService.Meeting ingest(@Valid @RequestBody NewMeeting m) {
        return service.ingest(m.title(), m.date(), m.series(), m.transcript());
    }

    @PostMapping(value = "/meetings/audio", consumes = "multipart/form-data")
    public ResponseEntity<?> ingestAudio(@RequestPart("file") MultipartFile file, @RequestParam String title,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                         @RequestParam(required = false) String series) throws IOException {
        if (!stt.available()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "No speech-to-text provider configured; POST a text transcript to /api/meetings"));
        }
        return ResponseEntity.ok(service.ingest(title, date, series, stt.transcribe(file.getBytes(), file.getOriginalFilename())));
    }

    @GetMapping("/meetings")
    public List<MeetingService.Meeting> meetings() {
        return service.meetings();
    }

    @GetMapping("/meetings/{id}")
    public MeetingService.Meeting meeting(@PathVariable String id) {
        return service.meeting(id);
    }

    @GetMapping("/items")
    public List<ActionItem> items(@RequestParam(required = false) String owner,
                                  @RequestParam(required = false) ItemStatus status,
                                  @RequestParam(defaultValue = "false") boolean overdue,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.items(owner, status, overdue, asOf == null ? LocalDate.now() : asOf);
    }

    @PostMapping("/items/{id}/review")
    public ActionItem review(@PathVariable String id, @RequestBody Review r) {
        return service.review(id, r.approve(), r.owner(), r.deadline(), r.action());
    }

    @PostMapping("/items/{id}/done")
    public ActionItem done(@PathVariable String id) {
        return service.markDone(id);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.dashboard(asOf == null ? LocalDate.now() : asOf);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
