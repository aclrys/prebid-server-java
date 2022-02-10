package org.prebid.server.bidder.grid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.response.Bid;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.grid.model.responce.GridBid;
import org.prebid.server.bidder.grid.model.responce.GridBidResponse;
import org.prebid.server.bidder.grid.model.responce.GridBidderBid;
import org.prebid.server.bidder.grid.model.responce.GridSeatBid;
import org.prebid.server.bidder.grid.model.request.ExtImp;
import org.prebid.server.bidder.grid.model.request.ExtImpGridData;
import org.prebid.server.bidder.grid.model.request.ExtImpGridDataAdServer;
import org.prebid.server.bidder.grid.model.request.Keywords;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.grid.ExtImpGrid;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.util.ObjectUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class GridBidder implements Bidder<BidRequest> {

    private final String endpointUrl;
    private final JacksonMapper mapper;
    private final GridKeywordsProcessor gridKeywordsProcessor;

    public GridBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
        this.gridKeywordsProcessor = new GridKeywordsProcessor(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();
        final List<Imp> imps = request.getImp();
        final List<Imp> modifiedImps = modifyImps(imps, errors);

        if (modifiedImps.isEmpty()) {
            errors.add(BidderError.badInput("No valid impressions for grid"));
            return Result.withErrors(errors);
        }

        final Keywords firstImpKeywords = getKeywordsFromImpExt(imps.get(0).getExt());
        final BidRequest modifiedRequest = modifyRequest(request, firstImpKeywords, modifiedImps);
        final HttpRequest<BidRequest> httpRequest =
                HttpRequest.<BidRequest>builder()
                        .uri(endpointUrl)
                        .method(HttpMethod.POST)
                        .headers(HttpUtil.headers())
                        .payload(modifiedRequest)
                        .body(mapper.encodeToBytes(modifiedRequest))
                        .build();

        return Result.of(Collections.singletonList(httpRequest), errors);
    }

    private List<Imp> modifyImps(List<Imp> imps, List<BidderError> errors) {
        final List<Imp> modifiedImps = new ArrayList<>();
        for (Imp imp : imps) {
            try {
                modifiedImps.add(modifyImp(imp, parseAndValidateImpExt(imp)));
            } catch (IllegalArgumentException | PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }

        return modifiedImps;
    }

    private ExtImp parseAndValidateImpExt(Imp imp) {
        final ExtImp extImp = mapper.mapper().convertValue(imp.getExt(), ExtImp.class);
        validateImpExt(extImp, imp.getId());
        return extImp;
    }

    private static void validateImpExt(ExtImp extImp, String impId) {
        final ExtImpGrid extImpGrid = extImp != null ? extImp.getBidder() : null;
        final Integer uid = extImpGrid != null ? extImpGrid.getUid() : null;
        if (uid == null || uid == 0) {
            throw new PreBidException(String.format("Empty uid in imp with id: %s", impId));
        }
    }

    private Imp modifyImp(Imp imp, ExtImp extImp) {
        final ExtImpGridData extImpData = extImp.getData();
        final ExtImpGridDataAdServer adServer = extImpData != null ? extImpData.getAdServer() : null;
        final String adSlot = adServer != null ? adServer.getAdSlot() : null;

        if (StringUtils.isNotEmpty(adSlot)) {
            final ExtImp modifiedExtImp = extImp.toBuilder()
                    .gpid(adSlot)
                    .build();
            return imp.toBuilder()
                    .ext(mapper.mapper().valueToTree(modifiedExtImp))
                    .build();
        }
        return imp;
    }

    private Keywords getKeywordsFromImpExt(JsonNode extImp) {
        try {
            return getExtImpGridBidder(extImp).getKeywords();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ExtImpGrid getExtImpGridBidder(JsonNode extImp) {
        return mapper.mapper().convertValue(extImp, ExtImp.class).getBidder();
    }

    private BidRequest modifyRequest(BidRequest bidRequest, Keywords firstImpKeywords, List<Imp> imp) {
        final User user = bidRequest.getUser();
        final String userKeywords = user != null ? user.getKeywords() : null;
        final Site site = bidRequest.getSite();
        final String siteKeywords = site != null ? site.getKeywords() : null;

        final ExtRequest extRequest = bidRequest.getExt();
        final Keywords resolvedKeywords = buildBidRequestExtKeywords(
                userKeywords, siteKeywords, firstImpKeywords, getKeywordsFromRequestExt(extRequest));

        return bidRequest.toBuilder()
                .imp(imp)
                .ext(modifyExtRequest(extRequest, resolvedKeywords))
                .build();
    }

    private Keywords getKeywordsFromRequestExt(ExtRequest extRequest) {
        try {
            final JsonNode requestKeywordsNode = extRequest != null ? extRequest.getProperty("keywords") : null;
            return requestKeywordsNode != null
                    ? mapper.mapper().treeToValue(requestKeywordsNode, Keywords.class)
                    : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Keywords buildBidRequestExtKeywords(String userKeywords,
                                                String siteKeywords,
                                                Keywords firstImpExtKeywords,
                                                Keywords requestExtKeywords) {

        final String resolvedUserKeywords = ObjectUtils.defaultIfNull(userKeywords, "");
        final String resolvedSiteKeywords = ObjectUtils.defaultIfNull(siteKeywords, "");

        return gridKeywordsProcessor.merge(
                gridKeywordsProcessor.resolveKeywordsFromOpenRtb(resolvedUserKeywords, resolvedSiteKeywords),
                gridKeywordsProcessor.resolveKeywords(firstImpExtKeywords),
                gridKeywordsProcessor.resolveKeywords(requestExtKeywords));
    }

    private ExtRequest modifyExtRequest(ExtRequest extRequest, Keywords keywords) {
        final ExtRequestPrebid extRequestPrebid = ObjectUtil.getIfNotNull(extRequest, ExtRequest::getPrebid);
        final Map<String, JsonNode> extRequestProperties = ObjectUtil.getIfNotNullOrDefault(
                extRequest, ExtRequest::getProperties, Collections::emptyMap);

        final Map<String, JsonNode> modifiedExtRequestProperties =
                gridKeywordsProcessor.modifyWithKeywords(extRequestProperties, keywords);
        if (modifiedExtRequestProperties.isEmpty()) {
            return null;
        }

        final ExtRequest modifiedBidRequestExt = ExtRequest.of(extRequestPrebid);
        modifiedBidRequestExt.addProperties(modifiedExtRequestProperties);
        return modifiedBidRequestExt;
    }

    @Override
    public final Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final GridBidResponse bidResponse =
                    mapper.decodeValue(httpCall.getResponse().getBody(), GridBidResponse.class);
            return Result.of(extractBids(httpCall.getRequest().getPayload(), bidResponse), Collections.emptyList());
        } catch (DecodeException | PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidRequest bidRequest, GridBidResponse gridBidResponse) {
        if (gridBidResponse == null || CollectionUtils.isEmpty(gridBidResponse.getSeatbid())) {
            return Collections.emptyList();
        }
        return bidsFromResponse(bidRequest, gridBidResponse);
    }

    private List<BidderBid> bidsFromResponse(BidRequest bidRequest, GridBidResponse gridBidResponse) {
        return gridBidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(GridSeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(gridBid -> resolveGridBidderBid(gridBid, bidRequest.getImp(), gridBidResponse.getCur()))
                .map(GridBidder::toBidderBid)
                .collect(Collectors.toList());
    }

    private GridBidderBid resolveGridBidderBid(GridBid gridBid, List<Imp> imps, String currency) {
        final GridBid modifiedGridBid = gridBid.toBuilder().ext(modifyBidExt(gridBid)).build();
        return GridBidderBid.of(modifiedGridBid, resolveBidType(gridBid, imps), currency);
    }

    private static BidderBid toBidderBid(GridBidderBid gridBidderBid) {
        return BidderBid.of(resolveBid(gridBidderBid.getBid()), gridBidderBid.getType(),
                gridBidderBid.getBidCurrency());
    }

    private static Bid resolveBid(GridBid gridBid) {
        return Bid.builder()
                .id(gridBid.getId())
                .impid(gridBid.getImpid())
                .price(gridBid.getPrice())
                .nurl(gridBid.getNurl())
                .burl(gridBid.getBurl())
                .lurl(gridBid.getLurl())
                .adm(gridBid.getAdm())
                .adid(gridBid.getAdid())
                .adomain(gridBid.getAdomain())
                .bundle(gridBid.getBundle())
                .iurl(gridBid.getIurl())
                .cid(gridBid.getCid())
                .crid(gridBid.getCrid())
                .cat(gridBid.getCat())
                .attr(gridBid.getAttr())
                .api(gridBid.getApi())
                .protocol(gridBid.getProtocol())
                .qagmediarating(gridBid.getQagmediarating())
                .language(gridBid.getLanguage())
                .dealid(gridBid.getDealid())
                .w(gridBid.getW())
                .h(gridBid.getH())
                .wratio(gridBid.getWratio())
                .hratio(gridBid.getHratio())
                .exp(gridBid.getExp())
                .ext(gridBid.getExt())
                .build();
    }

    private ObjectNode modifyBidExt(GridBid gridBid) {
        final String demandSource = ObjectUtils.defaultIfNull(gridBid.getExt(), MissingNode.getInstance())
                .at("/bidder/grid/demandSource").textValue();

        if (StringUtils.isEmpty(demandSource)) {
            return null;
        }

        final ExtBidPrebid extBidPrebid = ExtBidPrebid.builder()
                .meta(mapper.mapper().createObjectNode()
                        .set("demandsource", TextNode.valueOf(demandSource)))
                .build();
        return mapper.mapper().valueToTree(ExtPrebid.of(extBidPrebid, null));
    }

    private static BidType resolveBidType(GridBid gridBid, List<Imp> imps) {
        final BidType contentType = gridBid.getContentType();
        final String impId = gridBid.getImpid();
        if (contentType != null) {
            return contentType;
        } else {
            for (Imp imp : imps) {
                if (imp.getId().equals(impId)) {
                    if (imp.getBanner() != null) {
                        return BidType.banner;
                    }
                    if (imp.getVideo() != null) {
                        return BidType.video;
                    }
                    throw new PreBidException(String.format("Unknown impression type for ID: %s", impId));
                }
            }
        }
        throw new PreBidException(String.format("Failed to find impression for ID: %s", impId));
    }
}
