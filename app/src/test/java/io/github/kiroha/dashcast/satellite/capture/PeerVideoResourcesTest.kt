package io.github.kiroha.dashcast.satellite.capture
import org.junit.Assert.*
import org.junit.Test

class PeerVideoResourcesTest {
    @Test fun `unbind and native cleanup failures still release every owner once before replacement`() {
        val events=mutableListOf<String>()
        val resources=PeerVideoResources({events.add("invalidate")},{events.add("unbind");error("unbind")})
        for(name in listOf("factory","source","track","peer"))resources.own(name){events.add(it);error("release")}
        resources.close();resources.close();events.add("replacement")
        assertEquals(listOf("invalidate","unbind","peer","track","source","factory","replacement"),events)
    }
    @Test fun `each partial allocation prefix drains without a peer or another capture session`() {
        for(count in 0..4){val events=mutableListOf<String>();val resources=PeerVideoResources({events.add("invalidate")},{events.add("unbind")})
            val owners=listOf("factory","source","track","peer").take(count)
            owners.forEach{name->resources.own(name){events.add(it)}}
            resources.close();assertEquals(listOf("invalidate","unbind")+owners.reversed(),events)
        }
    }
}
