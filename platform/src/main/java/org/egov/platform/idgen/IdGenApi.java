package org.egov.platform.idgen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.egov.platform.idgen.model.IdGenerationRequest;
import org.egov.platform.idgen.model.IdGenerationResponse;
import org.egov.platform.idgen.model.IdRequest;
import org.egov.platform.idgen.model.RequestInfo;
import org.egov.platform.idgen.service.IdGenerationService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Public, in-process API of the {@code idgen} module.
 *
 * <p>This is the module's exposed surface: it lives in the module's top-level package and
 * returns only plain types ({@code List<String>}), so the {@code idgen} domain model
 * ({@link IdGenerationRequest}, {@link IdResponse}, etc.) stays internal to the module.
 * Other modules call this instead of doing an HTTP POST to {@code /egov-idgen/id/_generate}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdGenApi {

    private final IdGenerationService idGenerationService;

    /**
     * Generate {@code count} formatted IDs for the given name/format/tenant.
     * Replaces the former HTTP call from the {@code individual} module.
     */
    public List<String> generateIds(String tenantId, String idName, String format, int count) {
        IdRequest idRequest = new IdRequest();
        idRequest.setIdName(idName);
        idRequest.setTenantId(tenantId);
        idRequest.setFormat(format);
        idRequest.setCount(count);

        IdGenerationRequest request = new IdGenerationRequest();
        request.setRequestInfo(new RequestInfo());
        request.setIdRequests(List.of(idRequest));

        try {
            IdGenerationResponse response = idGenerationService.generateIdResponse(request);
            return response.getIdResponses().stream()
                    .map(org.egov.platform.idgen.model.IdResponse::getId)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("In-process ID generation failed", e);
        }
    }
}
