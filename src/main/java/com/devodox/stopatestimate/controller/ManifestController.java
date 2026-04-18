package com.devodox.stopatestimate.controller;

import com.cake.clockify.addonsdk.clockify.model.ClockifyManifest;
import com.devodox.stopatestimate.util.ManifestJsonSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManifestController {

    private final ClockifyManifest manifest;
    private final ObjectMapper objectMapper;

    public ManifestController(ClockifyManifest manifest, ObjectMapper objectMapper) {
        this.manifest = manifest;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> manifest() throws JsonProcessingException {
        JsonNode node = objectMapper.valueToTree(manifest);
        ManifestJsonSanitizer.stripEmptyArrays(node);
        return ResponseEntity.ok(objectMapper.writeValueAsString(node));
    }
}
