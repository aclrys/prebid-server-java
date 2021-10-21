package org.prebid.server.functional

import org.prebid.server.functional.model.db.StoredResponse
import org.prebid.server.functional.model.request.auction.BidRequest
import org.prebid.server.functional.model.request.auction.StoredAuctionResponse
import org.prebid.server.functional.model.response.auction.SeatBid
import org.prebid.server.functional.util.PBSUtils

class AuctionSpec extends BaseSpec {

    def "PBS should return info from stored response when it is defined in request"() {
        given: "Default basic BidRequest with stored response"
        def bidRequest = BidRequest.defaultBidRequest
        def storedResponseId = PBSUtils.randomNumber
        bidRequest.imp[0].ext.prebid.storedAuctionResponse = new StoredAuctionResponse(id: storedResponseId)

        and: "Stored response in DB"
        def responseData = SeatBid.getStoredResponse(bidRequest)
        def storedResponse = new StoredResponse(resid: storedResponseId, responseData: responseData)
        storedResponseDao.save(storedResponse)

        when: "PBS processes auction request"
        def response = defaultPbsService.sendAuctionRequest(bidRequest)

        then: "Response should contain information from stored response"
        assert response.id == bidRequest.id
        assert response.seatbid[0]?.seat == responseData.seat
        assert response.seatbid[0]?.bid?.size() == responseData.bid.size()
        assert response.seatbid[0]?.bid[0]?.impid == responseData.bid[0].impid
        assert response.seatbid[0]?.bid[0]?.price == responseData.bid[0].price
        assert response.seatbid[0]?.bid[0]?.id == responseData.bid[0].id

        and: "PBS not send request to bidder"
        assert bidder.getRequestCount(bidRequest.id) == 0
    }
}
