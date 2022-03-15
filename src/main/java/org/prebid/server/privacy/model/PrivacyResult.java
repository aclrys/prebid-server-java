package org.prebid.server.privacy.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PrivacyResult {

    Privacy validPrivacy;

    Privacy originPrivacy;

    List<String> errors;
}
