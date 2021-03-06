package org.prebid.server.auction.model;

import com.iab.openrtb.response.Bid;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Value(staticConstructor = "of")
public class CategoryMappingResult {

    Map<Bid, String> biddersToBidsCategories;

    Map<Bid, Boolean> biddersToBidsSatisfiedPriority;

    List<BidderResponse> bidderResponses;

    List<String> errors;

    public static CategoryMappingResult of(List<BidderResponse> bidderResponses) {
        return CategoryMappingResult.of(
                Collections.emptyMap(),
                Collections.emptyMap(),
                bidderResponses,
                Collections.emptyList());
    }

    public String getCategory(Bid bid) {
        return biddersToBidsCategories.get(bid);
    }

    public Boolean isBidSatisfiesPriority(Bid bid) {
        return biddersToBidsSatisfiedPriority.get(bid);
    }
}
