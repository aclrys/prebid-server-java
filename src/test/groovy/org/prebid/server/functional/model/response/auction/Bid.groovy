package org.prebid.server.functional.model.response.auction

import groovy.transform.ToString
import org.prebid.server.functional.model.request.auction.Imp
import org.prebid.server.functional.util.PBSUtils

import static java.math.RoundingMode.HALF_UP

@ToString(includeNames = true, ignoreNulls = true)
class Bid {

    String id
    String impid
    BigDecimal price
    String nurl
    String burl
    String lurl
    String adm
    String adid
    List<String> adomain
    String bundle
    String iurl
    String cid
    String crid
    List<String> cat
    List<Integer> attr
    Integer api
    Integer protocol
    Integer qagmediarating
    String language
    String dealid
    Integer w
    Integer h
    Integer wratio
    Integer hratio
    Integer exp
    BidExt ext

    static List<Bid> getDefaultBids(List<String> impIds) {
        impIds.collect { getDefaultBid(it) }
    }

    static Bid getDefaultBid(Imp imp) {
        getDefaultBid(imp.id)
    }

    static Bid getDefaultBid(String impId) {
        new Bid().tap {
            id = UUID.randomUUID()
            impid = impId
            price = BigDecimal.valueOf(PBSUtils.getFractionalRandomNumber(0, 10))
                              .setScale(3, HALF_UP)
            crid = 1
        }
    }
}
