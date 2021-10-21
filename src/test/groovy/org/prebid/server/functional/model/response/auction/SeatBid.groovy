package org.prebid.server.functional.model.response.auction

import groovy.transform.ToString
import org.prebid.server.functional.model.bidder.BidderName
import org.prebid.server.functional.model.request.auction.BidRequest
import org.prebid.server.functional.util.PBSUtils

@ToString(includeNames = true, ignoreNulls = true)
class SeatBid {

    List<Bid> bid
    BidderName seat
    Integer group

    static SeatBid getStoredResponse(BidRequest bidRequest) {
        def bids = Bid.getDefaultBids(bidRequest.imp*.id).each {
            it.h = PBSUtils.randomNumber
            it.w = PBSUtils.randomNumber
        }
        new SeatBid(bid: bids, seat: BidderName.GENERIC)
    }
}
