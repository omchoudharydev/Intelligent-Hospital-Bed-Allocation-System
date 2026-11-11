package com.ihbas.backend.documents;

import com.ihbas.backend.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@RestController
public class PatientDocumentController {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("IDENTITY", "INSURANCE", "REFERRAL", "CONSENT", "DISCHARGE_SUMMARY", "OTHER");
    private final JdbcTemplate jdbc;
    private final Path root;

    public PatientDocumentController(JdbcTemplate jdbc, @Value("${app.documents.directory:./data/patient-documents}") String directory) throws IOException {
        this.jdbc = jdbc;
        this.root = Paths.get(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @PostMapping(value = "/api/receptionist/patients/{patientId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable long patientId, @RequestParam("file") MultipartFile file,
                                     @RequestParam("documentType") String documentType) throws Exception {
        ensurePatient(patientId);
        String type = documentType.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document type.");
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a file up to 10 MB.");
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("document");
        String ext = extension(original);
        String mime = detectMime(file.getBytes(), ext);
        if (mime == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF, JPEG and PNG files are accepted.");
        String key = UUID.randomUUID().toString() + ext;
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name.");
        byte[] bytes = file.getBytes();
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        try {
            jdbc.update("INSERT INTO patient_documents(patient_id,document_type,storage_key,original_filename,mime_type,file_size_bytes,sha256_checksum,uploaded_by,uploaded_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP())",
                    patientId, type, key, safeName(original), mime, bytes.length, checksum, actorId());
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("documentId", id, "fileName", safeName(original), "documentType", type, "sizeBytes", bytes.length, "message", "Document uploaded securely.");
    }

    @GetMapping("/api/receptionist/patients/{patientId}/documents")
    public List<Map<String,Object>> receptionistList(@PathVariable long patientId) {
        ensurePatient(patientId);
        return jdbc.queryForList("SELECT document_id,document_type,original_filename,mime_type,file_size_bytes,uploaded_at FROM patient_documents WHERE patient_id=? ORDER BY uploaded_at DESC", patientId);
    }

    @GetMapping("/api/receptionist/patients/{patientId}/documents/{documentId}/download")
    public ResponseEntity<Resource> receptionistDownload(@PathVariable long patientId, @PathVariable long documentId) throws IOException {
        return download(patientId, documentId);
    }

    @GetMapping("/api/patient/documents")
    public List<Map<String,Object>> patientList() {
        long patientId = jdbc.queryForObject("SELECT patient_id FROM patients WHERE user_id=? AND record_status='ACTIVE'", Long.class, actorId());
        return receptionistList(patientId);
    }

    @GetMapping("/api/patient/documents/{documentId}/download")
    public ResponseEntity<Resource> patientDownload(@PathVariable long documentId) throws IOException {
        long patientId = jdbc.queryForObject("SELECT patient_id FROM patients WHERE user_id=? AND record_status='ACTIVE'", Long.class, actorId());
        return download(patientId, documentId);
    }

    private ResponseEntity<Resource> download(long patientId, long documentId) throws IOException {
        Map<String,Object> row = jdbc.queryForMap("SELECT storage_key,original_filename,mime_type FROM patient_documents WHERE document_id=? AND patient_id=?", documentId, patientId);
        Path file = root.resolve(String.valueOf(row.get("storage_key"))).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document file not found.");
        String mime = String.valueOf(row.get("mime_type"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(String.valueOf(row.get("original_filename"))).build().toString())
                .body(new FileSystemResource(file));
    }

    private void ensurePatient(long patientId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE patient_id=? AND record_status='ACTIVE'", Long.class, patientId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found.");
    }
    private long actorId() { return jdbc.queryForObject("SELECT user_id FROM users WHERE username=?", Long.class, RequestUser.username()); }
    private static String extension(String name) { int i=name.lastIndexOf('.'); return i<0?"":name.substring(i).toLowerCase(Locale.ROOT); }
    private static String safeName(String name) { String n=Paths.get(name).getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_"); return n.length()>240?n.substring(n.length()-240):n; }
    private static String detectMime(byte[] b, String ext) {
        if (b.length>=5 && b[0]=='%' && b[1]=='P' && b[2]=='D' && b[3]=='F' && b[4]=='-') return "application/pdf";
        if (b.length>=8 && (b[0]&255)==137 && b[1]=='P' && b[2]=='N' && b[3]=='G' && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10) return "image/png";
        if (b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "image/jpeg";
        return null;
    }
}
