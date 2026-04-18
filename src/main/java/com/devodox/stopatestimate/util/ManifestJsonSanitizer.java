package com.devodox.stopatestimate.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ManifestJsonSanitizer {

    private ManifestJsonSanitizer() {
    }

    /**
     * Recursively removes properties whose value is JSON {@code null} or an empty array.
     * Clockify's manifest JSON schema rejects {@code null} where a typed value is expected,
     * so the serialized manifest must omit optional fields rather than emit them as null.
     */
    public static JsonNode stripEmptyArrays(JsonNode node) {
        if (node == null) {
            return null;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> toRemove = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = objectNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                JsonNode child = entry.getValue();
                stripEmptyArrays(child);
                if (child.isNull() || (child.isArray() && child.isEmpty())) {
                    toRemove.add(entry.getKey());
                }
            }
            toRemove.forEach(objectNode::remove);
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode child : arrayNode) {
                stripEmptyArrays(child);
            }
        }
        return node;
    }
}
