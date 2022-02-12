package org.prebid.server.functional.tests.pricefloors

import org.prebid.server.functional.model.bidder.Generic
import org.prebid.server.functional.model.db.StoredRequest
import org.prebid.server.functional.model.mock.services.floorsprovider.PriceFloorRules
import org.prebid.server.functional.model.pricefloors.PriceFloorEnforcement
import org.prebid.server.functional.model.request.amp.AmpRequest
import org.prebid.server.functional.model.request.auction.BidRequest
import org.prebid.server.functional.model.request.auction.ExtPrebidFloors
import org.prebid.server.functional.model.request.auction.ExtPrebidPriceFloorEnforcement
import org.prebid.server.functional.model.request.auction.MultiBid
import org.prebid.server.functional.model.request.auction.Targeting
import org.prebid.server.functional.model.response.auction.Bid
import org.prebid.server.functional.model.response.auction.BidResponse
import org.prebid.server.functional.model.response.auction.ErrorType
import org.prebid.server.functional.util.PBSUtils

import static org.prebid.server.functional.model.bidder.BidderName.GENERIC

class PriceFloorsEnforcementSpec extends PriceFloorsBaseSpec {

    def "PBS should make PF enforcement for amp request when stored request #descriprion rules"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request #descriprion rules "
        def alias = "genericAlias"
        def bidderParam = PBSUtils.randomNumber
        def bidderAliasParam = PBSUtils.randomNumber
        def ampStoredRequest = BidRequest.defaultStoredRequest.tap {
            ext.prebid.aliases = [(alias): GENERIC]
            imp[0].ext.prebid.bidder.genericAlias = new Generic(firstParam: bidderParam)
            imp[0].ext.prebid.bidder.generic.firstParam = bidderAliasParam
            ext.prebid.floors = floors
        }
        def storedRequest = StoredRequest.getDbStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(ampRequest.account as String, floorsResponse)

        and: "Bid response for generic bidder"
        def bidResponse = BidResponse.getDefaultBidResponse(ampStoredRequest).tap {
            seatbid.first().bid.first().price = floorValue
        }
        bidder.setResponse(bidder.getRequest("imp[0].ext.bidder.firstParam", bidderParam as String), bidResponse)

        and: "Bid response for generic bidder alias"
        def aliasBidResponse = BidResponse.getDefaultBidResponse(ampStoredRequest).tap {
            seatbid.first().bid.first().price = floorValue - 0.1
        }
        bidder.setResponse(bidder.getRequest("imp[0].ext.bidder.firstParam", bidderAliasParam as String), aliasBidResponse)

        when: "PBS processes amp request"
        def response = floorsPbsService.sendAmpRequest(ampRequest)

        then: "PBS should suppress bids lower than floorRuleValue"
        verifyAll(response) {
            targeting["hb_pb_generic"] == floorValue
            targeting["hb_pb"] == floorValue
            !targeting["hb_pb_genericAlias"]
        }

        and: "PBS should log warning about bid suppression"
        assert response.ext?.warnings[ErrorType.PREBID]*.code == [999]
        assert response.ext?.warnings[ErrorType.PREBID]*.message == ["placeholder"]

        where:
        descriprion       | floors
        "doesn't contain" | null
        "contains"        | ExtPrebidFloors.extPrebidFloors
    }

    def "PBS should reject bids when ext.prebid.floors.enforcement.enforcePBS = #enforcePbs"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.multibid = [new MultiBid(bidder: GENERIC, maxBids: 2)]
            ext.prebid.floors = new ExtPrebidFloors(enforcement: new ExtPrebidPriceFloorEnforcement(enforcePbs: enforcePbs))
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: price = floorValue, price < floorValue"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().price = floorValue
            seatbid.first().bid.last().price = floorValue - 0.1
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert response.seatbid?.first()?.bid?.collect { it.price } == [floorValue]

        and: "PBS should log warning about bid suppression"
        assert response.ext?.warnings[ErrorType.PREBID]*.code == [999]
        assert response.ext?.warnings[ErrorType.PREBID]*.message == ["placeholder"]

        where:
        enforcePbs << [true, null]
    }

    def "PBS should not reject bids when ext.prebid.floors.enforcement.enforcePBS = false"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.multibid = [new MultiBid(bidder: GENERIC, maxBids: 2)]
            ext.prebid.floors = new ExtPrebidFloors(enforcement: new ExtPrebidPriceFloorEnforcement(enforcePbs: false))
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: price = floorValue, price < floorValue"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().price = floorValue
            seatbid.first().bid.last().price = floorValue - 0.1
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert response.seatbid?.first()?.bid?.collect { it.price }?.sort() ==
                bidResponse.seatbid.first().bid.collect { it.price }.sort()
    }

    def "PBS should make PF enforcement when imp[].bidfloor/cur comes from request"() {
        given: "Default BidRequest with floors"
        def bidRequest = bidRequestWithFloors

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: price = floorValue, price < floorValue"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().price = floorValue
            seatbid.first().bid.last().price = floorValue - 0.1
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert response.seatbid?.first()?.bid?.collect { it.price } == [floorValue]
    }

    def "PBS should suppress deal that are below the matched floor when enforce-deal-floors = true"() {
        given: "Pbs with PF configuration with enforceDealFloors"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.enforceDealFloors = false
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "Default basic  BidRequest with generic bidder with preferdeals = true"
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.targeting = new Targeting(preferdeals: true)
        }

        and: "Account with enabled fetch, fetch.url,enforceDealFloors in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.enforceDealFloors = true
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
            enforcement = new PriceFloorEnforcement(floorDeals: true)
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: bid.price = floorValue, dealBid.price < floorValue"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().dealid = PBSUtils.randomNumber
            seatbid.first().bid.first().price = floorValue - 0.1
            seatbid.first().bid.last().price = floorValue
        }

        and: "Set bidder response"
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should suppress bid lower than floorRuleValue"
        assert response.seatbid?.first()?.bid?.first()?.id == bidResponse.seatbid.first().bid.last().id
        assert response.seatbid.first().bid.collect { it.price } == [floorValue]
    }

    def "PBS should not suppress deal that are below the matched floor according to ext.prebid.floors.enforcement.enforcePBS"() {
        given: "Pbs with PF configuration with enforceDealFloors"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.enforceDealFloors = pbsConfigEnforceDealFloors
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "Default basic BidRequest with generic bidder with preferdeals = true"
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.targeting = new Targeting(preferdeals: true)
            ext.prebid.floors = new ExtPrebidFloors(enforcement: new ExtPrebidPriceFloorEnforcement(enforcePbs: enforcePbs))
        }

        and: "Account with enabled fetch, fetch.url, enforceDealFloors in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.enforceDealFloors = accountEnforceDealFloors
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
            enforcement = new PriceFloorEnforcement(floorDeals: floorDeals)
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: bid.price = floorValue, dealBid.price < floorValue"
        def dealBidPrice = floorValue - 0.1
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().dealid = PBSUtils.randomNumber
            seatbid.first().bid.first().price = dealBidPrice
            seatbid.first().bid.last().price = floorValue
        }

        and: "Set bidder response"
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should choose bid with deal"
        assert response.seatbid?.first()?.bid?.first()?.id == bidResponse.seatbid.first().bid.first().id
        assert response.seatbid.first().bid.collect { it.price } == [dealBidPrice]

        where:
        pbsConfigEnforceDealFloors | enforcePbs | accountEnforceDealFloors | floorDeals
        true                       | null       | false                    | true
        false                      | false      | true                     | true
        false                      | null       | true                     | false
    }

    def "PBS should suppress any bids below the matched floor when fetch.enforce-floors-rate = 100 in account config"() {
        given: "Pbs with PF configuration with minMaxAgeSec"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.enforceFloorsRate = pbsConfigEnforceFloorsRate
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.multibid = [new MultiBid(bidder: GENERIC, maxBids: 2)]
            ext.prebid.floors = new ExtPrebidFloors(enforcement: new ExtPrebidPriceFloorEnforcement(enforceRate: enforceRate))
        }

        and: "Account with enabled fetch, fetch.url, enforceFloorsRate in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.enforceFloorsRate = accountEnforceFloorsRate
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
            enforcement = new PriceFloorEnforcement(floorDeals: true)
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: price = floorValue, price < floorValue"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().price = floorValue - 0.1
            seatbid.first().bid.last().price = floorValue
        }

        and: "Set bidder response"
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should suppress inappropriate bids"
        assert response.seatbid?.first()?.bid?.size() == 1
        assert response.seatbid?.first()?.bid?.first()?.id == bidResponse.seatbid.first().bid.last().id
        assert response.seatbid.first().bid.collect { it.price } == [floorValue]

        and: "Bidder request enforcePbs should correspond to true"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.ext?.prebid?.floors?.enforcement?.enforcePbs

        where:
        pbsConfigEnforceFloorsRate       | enforceRate | accountEnforceFloorsRate
        PBSUtils.getRandomNumber(0, 100) | null        | 100
        PBSUtils.getRandomNumber(0, 100) | 100         | null
        100                              | null        | null
    }

    def "PBS should not suppress any bids below the matched floor when fetch.enforce-floors-rate = 0 in account config"() {
        given: "Pbs with PF configuration with minMaxAgeSec"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.enforceFloorsRate = pbsConfigEnforceFloorsRate
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.multibid = [new MultiBid(bidder: GENERIC, maxBids: 2)]
            ext.prebid.floors = new ExtPrebidFloors(enforcement: new ExtPrebidPriceFloorEnforcement(enforceRate: enforceRate))
        }

        and: "Account with enabled fetch, fetch.url, enforceFloorsRate in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.enforceFloorsRate = accountEnforceFloorsRate
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
            enforcement = new PriceFloorEnforcement(floorDeals: true)
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "Bid response with 2 bids: price = floorValue, price < floorValue"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid << Bid.getDefaultBid(bidRequest.imp.first())
            seatbid.first().bid.first().price = floorValue - 0.1
            seatbid.first().bid.last().price = floorValue
        }

        and: "Set bidder response"
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should not suppress bids"
        assert response.seatbid?.first()?.bid?.size() == 2
        assert response.seatbid.first().bid.collect { it.price } == [floorValue, floorValue - 0.1]

        and: "Bidder request enforcePbs should correspond to false"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert !bidderRequest.ext?.prebid?.floors?.enforcement?.enforcePbs

        where:
        pbsConfigEnforceFloorsRate       | enforceRate | accountEnforceFloorsRate
        PBSUtils.getRandomNumber(0, 100) | null        | 0
        0                                | 0           | null
        PBSUtils.getRandomNumber(0, 100) | 100         | 0
        PBSUtils.getRandomNumber(0, 100) | 0           | 100
        0                                | null        | null
    }
}
