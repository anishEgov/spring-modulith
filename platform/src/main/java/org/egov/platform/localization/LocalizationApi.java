package org.egov.platform.localization;

import lombok.RequiredArgsConstructor;
import org.egov.platform.localization.domain.model.Message;
import org.egov.platform.localization.domain.model.MessageSearchCriteria;
import org.egov.platform.localization.domain.model.Tenant;
import org.egov.platform.localization.domain.service.MessageService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public, in-process API of the {@code localization} module.
 *
 * <p>Lives in the module's top-level package and returns a plain {@code Map<code,message>},
 * keeping the localization domain model ({@link Message}, {@link MessageSearchCriteria},
 * {@link Tenant}) internal. Other modules call this instead of doing an HTTP POST to
 * {@code /localization/messages/v1/_search}.
 */
@Service
@RequiredArgsConstructor
public class LocalizationApi {

    private final MessageService messageService;

    /**
     * Fetch localized messages (code -&gt; message) for a tenant/module/locale.
     * Replaces the former HTTP call from the {@code individual} module.
     */
    public Map<String, String> getMessages(String tenantId, String module, String locale, Set<String> codes) {
        MessageSearchCriteria criteria = MessageSearchCriteria.builder()
                .tenantId(new Tenant(tenantId))
                .module(module)
                .locale(locale)
                .codes(codes)
                .build();

        List<Message> messages = messageService.getFilteredMessages(criteria);

        Map<String, String> result = new HashMap<>();
        for (Message m : messages) {
            result.put(m.getCode(), m.getMessage());
        }
        return result;
    }
}
