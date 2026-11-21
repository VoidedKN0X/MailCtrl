package com.vknxworks.mailctrl.smime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage
import com.vknxworks.mailctrl.pgp.PgpMime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * S/MIME against files made by OpenSSL 3.0 (`openssl pkcs12`, `openssl cms`): a test CA and "Alice" (RSA 2048,
 * alice@example.org). The certificates in these files expire, so nothing here checks dates.
 */
class SmimeEngineTest {
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    private val inner = "Content-Type: text/plain; charset=utf-8\r\n\r\nHello from OpenSSL\r\n".toByteArray()

    @Test
    fun readsP12FromOpenSsl3AndLegacy() {
        for (file in listOf(P12, P12_LEGACY)) {
            val id = Pkcs12.read(b64(file), "s3cret".toCharArray())
            val info = SmimeEngine.info(id.cert)
            assertEquals("Alice", info.name)
            assertEquals(listOf("alice@example.org"), info.emails)
            assertTrue(info.canSign && info.canEncrypt)
            assertEquals(2, id.chain.size)
            assertEquals("Test CA", SmimeEngine.info(id.chain[1]).name)
        }
    }

    @Test
    fun wrongPasswordIsSaidSo() {
        try {
            Pkcs12.read(b64(P12), "nope".toCharArray())
            fail()
        } catch (e: SmimeFailure) {
            assertEquals(SmimeFailure.Kind.BAD_PASSWORD, e.kind)
        }
    }

    @Test
    fun checksOpenSslSignatures() {
        assertEquals(SmimeSigStatus.VALID, SmimeEngine.verify(inner, b64(DETACHED)).status)
        assertEquals(SmimeSigStatus.BAD, SmimeEngine.verify(inner + 'x'.code.toByte(), b64(DETACHED)).status)
        val opaque = SmimeEngine.verify(null, b64(OPAQUE))
        assertEquals(SmimeSigStatus.VALID, opaque.status)
        assertArrayEquals(inner, opaque.content)
    }

    @Test
    fun decryptsOpenSslOaep() {
        val id = Pkcs12.read(b64(P12), "s3cret".toCharArray())
        assertArrayEquals(inner, SmimeEngine.decrypt(b64(ENCRYPTED_OAEP), listOf(id)))
    }

    @Test
    fun signsAndEncryptsWhatItReads() {
        val id = Pkcs12.read(b64(P12), "s3cret".toCharArray())
        val sig = SmimeEngine.sign(inner, id)
        assertEquals(SmimeSigStatus.VALID, SmimeEngine.verify(inner, sig).status)
        val env = SmimeEngine.encrypt(inner, listOf(id.cert))
        assertArrayEquals(inner, SmimeEngine.decrypt(env, listOf(id)))
    }

    @Test
    fun signedMultipartRoundTrip() {
        val id = Pkcs12.read(b64(P12), "s3cret".toCharArray())
        val part = PgpMime.Inner().apply { setText("Hello", "UTF-8"); setHeader("Content-Transfer-Encoding", "quoted-printable") }
        val bytes = PgpMime.serialize(part)
        val session = Session.getInstance(Properties())
        val m = MimeMessage(session)
        m.setContent(SmimeMime.signed(bytes, SmimeEngine.sign(bytes, id)))
        m.saveChanges()
        val raw = ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
        val found = SmimeMime.find(MimeMessage(session, ByteArrayInputStream(raw))) as SmimeMime.Found.Signed
        val content = PgpMime.signedContent(raw, found.boundary)
        assertNotNull(content)
        assertEquals(SmimeSigStatus.VALID, SmimeEngine.verify(content, found.signature).status)
    }

    private companion object {
        const val P12 =
            "MIINugIBAzCCDXAGCSqGSIb3DQEHAaCCDWEEgg1dMIINWTCCB7IGCSqGSIb3DQEHBqCCB6MwggefAgEAMIIHmAYJKoZIhvcNAQcB" +
            "MFcGCSqGSIb3DQEFDTBKMCkGCSqGSIb3DQEFDDAcBAhTXhVCv4+IEwICCAAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEFfM" +
            "ubw3VK+524tFcb/oCpSAggcw5Wo0hIZfsj2uGDr9uRJkoaTIvISmNhlV/IOWthO7thbCPAyF1tJDL07oS6ZVBBAGRol1YfjbYt5i" +
            "2KVdmAJmE8k8MbEtz17p9TO6NrprfSgrO86ME5ddZjnb/WizR9iHcV1+HJxt69cxuFpCS6gzltaFVD9BMwpzL8dPeQDmPshmJNwv" +
            "gNNQz1a60AZ2YDCEmre4efNwEcrU34rWtH6iHDq9neGv9Ab1ncehkjZv7EGZWhLP9/wBQcrVWrIWhxftSi2a4+OmLkFjPK712Zb3" +
            "O0bZptBa0z2J4CKbkWhN0uzatwtdXkSihK1RQHVxHcQQGpVKHNyg4nukDOeqGbmzVcJ+6XW5dcpcc1Pkh4+DOuVO/PE68O6j1G3y" +
            "4K1BCOJ5o1lmhw6pEOrUjYq5HJddjfHt57xtb3mq84ZPvvMZU3Lz9srga+Gs/9ME+ShaC2niW2fSlZZSmosA2igeoghHGNGsOIFN" +
            "fJsmOqQcjMEislPbloyIvy/o1qME1MFzGyTO+xUE49cbG520xvXPeMOzi9tIK+LoEeeqP80K9EQjRphQbe6YL7j8R5Cblknw2qyi" +
            "4IDs/VjjMCdjKKKd/r/p24Qf2Rqyfrix/4320vY2YaSg1/2mBWN21uP+5I0HJkJDA7tPOkcFTwZq1tYafqwV+VR93q2BKfx46wbU" +
            "03Lvu+vBGe/KRgYRzfQsGI4YOzrhv5Nh1CQrjJUsSqV9yNTU6OXaxJroc7BXv6HCtPeUm4cbpPExj3dE5b0PDujAQTFnEx0Gpr2X" +
            "Jw7CsP9aX06xIlSeIkL4Ca1p/vwSKXPk79UXepiI9vqpANX4c3asdVYJv8cBOzklJTMJx1FLtloKpMXwkJw5jatT4n1Zn6ZlmawS" +
            "sEzU5qP6j1xe55697swQqfSD2rnuvALIYfOvxox8NkEJMmhxPrlbRVEXodI99trN/y5lszVGOnKVv5zHfK/jEbAUZGc2k3QA88kF" +
            "nHhrHGx0EVtQMAUSlpzuhplVJtY53mpLMrhOx46x+XPKrVqUHzsm4UDgvHzpqkHp7Hm42t4AoHAf6Mw7SxvCxWp3zC7ePH/0LSpo" +
            "pVX3L6pIBK07Yl8KO77qXj8JVMgLVXPgVVImzM2q5GMhS4T7NambPmAY5zg/GyzvnOMVsLDvGgUVN3hPmp0VjCDxa2N2OivkGR8O" +
            "uYliLTH6e72JREuQgqwh6cqSJPyKBorxbjVezjexUEIBwKxDHTVyZBkTODVCykrckrxyDtJ4JPUxhMqgqMoj58/s7Y/LHnFbYP/u" +
            "lybzWZ/VmcID0AG7uaqMC59CM1XeB+b2MTFXlMxoKvtUkxg3N2plNxYLJ5U5xpn6DNqr1S4lEvjITZGOV4+gWnsVKi+G2Plt/OzT" +
            "gnG6EodG9yXlcCcm+7iU5amwF/n0VZHO4V11G6NV4g/rLBFi2DVsdPrZg3pAOqCJVZQA5+mU4gITgEUTlZy50KDoiSl2hDDRKky7" +
            "HrzhwjK2A36K0rY4Xb0HoKHsNR4Eh3Gs8GMC9bhrw3gytaCWp68PGx0Cn5Hb9EpY9KKMcHBRGYHYu/r9adfWJzhHEoSD9fMUFB6W" +
            "7rA1X0ge55COSr02TXp7zZRNeXLrXNFCs+skF47QS6GLY5zYEUhYDAqJaWTTw8vBhNkFrYEM83vpqeryAL0vrCvibbVoxHZg047G" +
            "iHa+UlHUK6tiVEYRgJSsSGkHR4Mq3raNUpAZOpqjj5hPB9aHnWNdeMITfKlbthkk9Ga0C1boBU4wUltboMeEE4PhS0PLfzrzjElP" +
            "yFOUlgzG6yubA9Uqa/zzntvXymBrkXYgKYAQ+DTHoRJ4igvXs3bOYkfXtd7dzA094aTwPfaPsN75L8Vz/0O2X+LylXTMqRNr6TTw" +
            "cc87RdmDfGxOERt296fGALYXLmz3S9Y2C2P+lSmfXFL6i809Vq1j1etPFT8KlZSfBEzWiT+emeKw5xlaJAWDfZ71t5TzHGmWRidp" +
            "KyWQhzRqL+EdSw8NPtlv1sAmNVHLFPIGLy5YUi2g2C7TJrSX6liwfdsrjQAX8i0DSV9SdanS35YElhRkEg79DLXHHJSp/iCA3tCd" +
            "5VWGl8ogcme6/At3zYAVLtivHz6iq7KZnUZyWRzW3+M9IZCvCxjB7LTb7UqKzMir7V5x8iawLp151MDYGYuROhXXIfOO+/h1ET9B" +
            "9sbpR+098uyv/XpuztaZa5ZVIxgGMqFbbUajwwKJRpx7GTAG2AbniRCnd4tYqiZTlf96aHEncAJQII835a3UBJ+EYppsKq3lPJng" +
            "nsrSORLj0/Zor65MgaP+3VPPRAQyx/HYM7f+w2/Nzw9ipLh0E7gC38vHH/ARMdjfnOYOgSkhDihSaB05/k8H0DfKmvGogq2Cv+qK" +
            "4CqsJNBm1fqGqvgepl39FQB1dINXq5CRWOlopLn3KvYcu4cz7UMtttuSz/7SI1uZ+dKbVeXJI5uQajCCBZ8GCSqGSIb3DQEHAaCC" +
            "BZAEggWMMIIFiDCCBYQGCyqGSIb3DQEMCgECoIIFMTCCBS0wVwYJKoZIhvcNAQUNMEowKQYJKoZIhvcNAQUMMBwECDJKR8RAWgUy" +
            "AgIIADAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQn0YwZdf5oMMoXWXmOfsQFgSCBNCF5G6at2pf/xSBb9JRQYOUEyZeHbpY" +
            "OOqyCdBtArvC2h25SZbxEeQ/ObVucf3y5+y1IZmXLyrGukduhRO5jBnLUPDK5jhtcPRJCMjf59SF5Z+upfjiNY0hY/P01cv3IMLP" +
            "hu3FmO+vHn0V6fK9kt0MB4W5nUQawScW6UohoMim/bnOJYEcKyd+3AOe91zfw5nltzz3y+N+oKRNkzx6eAO1Ki25NS3KLfm9Adxv" +
            "jhEgTyziImt5IoYkZS74+HCcz8BZoAhWxObE7A7b5V3/sHHyZKXh/fzWHiSC3bkybvmevqgwtwu8t2CS+KeGoFSc04ITpArkj9Y6" +
            "tQhB9z6M8utT2ozgEnNIChWGA2qhVeoerggT5JfDDRYW6H4QiyoAdUNYfM9IxW96BOQZo5Jr1Xf9a9JwNWcC5Npy6Iebwr4N+8Cq" +
            "CYAb2Rt5HD7fCl9amMIimOA1wd/xZJ08iuQHSPInjUZzT8YWWr4ibmf4SvS+jHM2m6qMENhaDV+O0oJ64O/FO0szyqAnVpXwBXyc" +
            "Nrg9NbNVCoy/6FGwKFj6J1+8ISOiPV8Gc6gGJsi8215xgKoa3JYogQoL9sKfeY9xEwNQ14ikNC0CK7O9dGuWyil/bld3VEwxUUeZ" +
            "m5KbwgwPCIe4e5aDGD+1op1dcvigfCREnnIS1V4tRjC8zY4I6wfoU/R/JZLeOH6DR2oWnCn/MhWRyj027emg55H+5LA8UvCKbkIE" +
            "6TMJSSTs17ejrNRPtVo3OO5ySFZExtTwoyTjpPVY6GvIT3w+upoqyxzo4tcDMpZREaVRL+CqD5Mpm29Fc1gHB9GgCrX2cL/pih4p" +
            "p7/tB67QHj6dYTQo3Jvv7kDmK293q6Kpd1D7dqcy5jfS05REMz75mUXpkd/osfdrjnPTWe/Nke6ohJxNP4d/bxxSL3HvZjqP0Z3T" +
            "z+yoTpBBjBtn+pO1yXiErIbngPeewebUYyMYHLEIYjuxh5eQMOPFwmMxfdiKwG8KDs/hVPF2ORFb0o55Q75qJ5grELfGJPmukh7P" +
            "eQeeEkdp8oLLV97sccNxFp1jSJza7JG9SKUEmx2HR3YYSVfMC9uVZ//ks06CKqLRR6SaZvpTpk6CjpOwIC/LMlm7KIXST9Jpus1r" +
            "2FdJohAabyl6bPsFGPangGSovCGFvqfcsaz6Zb4xgTpCn6Tk59NJBQo1ozRZ3hThDpgE4D+R+O/2wC+G6aeyKB68QeQRgTO+X2Ie" +
            "Y7szGssuXbmSJx7RPDrZclxgySsKRl90OFGVf1Tw2YzTZMTX87/cv04Dk1KVGhOCYXTNvIYlY6ltcWJcCQuSOXfnhjHDw3+hO3Rl" +
            "zOzTrteGzwRmQdd8ryGpkGFwyBx3UErTIYmzpTLlowXBn9bwgazCyZ36MovQ4NwHPpvZRoYQ6w1S71T0akHidg/Gv8t6OaTdW7pT" +
            "FWnVNbEOsQj65FQvDgqubzRHMWAzXgqS3VmZE6lWFMg4r0JjKD5aapRTDDuQBbGRKvrt4uBMzrMiln951qi7JvT2r8CmfERA/nB9" +
            "3+50hz9125Hy8Q6pSh7XaAGTZn5q93dJ3XTWOP7zTPUY54IC+V7TqUoLd+x7wlTiKXdTN1pQPjxK3TF8+h1x31VGYSFIOzRoHRz7" +
            "zVvyywE1eN8qqTFAMBkGCSqGSIb3DQEJFDEMHgoAYQBsAGkAYwBlMCMGCSqGSIb3DQEJFTEWBBS+eEeIufRlCby8hcENXyyg5XCt" +
            "NDBBMDEwDQYJYIZIAWUDBAIBBQAEIDO/4aUkssVPNAcle5npKTLLc52wWIdCOJv7Hfci2r+7BAjil6MBJXM80AICCAA="
        const val P12_LEGACY =
            "MIIM6QIBAzCCDK8GCSqGSIb3DQEHAaCCDKAEggycMIIMmDCCB08GCSqGSIb3DQEHBqCCB0Awggc8AgEAMIIHNQYJKoZIhvcNAQcB" +
            "MBwGCiqGSIb3DQEMAQYwDgQId79++5DREAoCAggAgIIHCM7dHPRxwahJ5SunL3nLTPMsNzbjA+OZN4Dl3i7OLuB3T2A0MHBcOm6N" +
            "oLsOOoPf/U6IGDBbzHcy4b4C3irliuFhS9B96H3suZdVCoaIdvcdCan08OoZvVnkHPhBxYvFt4XK/WgNISzRutCTQSexrobV+Up3" +
            "ZoCXfitP25+NeXpffu8A6C4UX+fDWJse7WfZSa7n2XAbRJJmqimFjqGovLvZIdFxvlO1TGtcf1GYk48LM4Kw+Ptwqobkhgpaglmp" +
            "aLmbZj2//DKyD2UbGvtPhQS4V65u1cmQNYFutgB3f6EsSX7vkR7Nv8RmZ/KJJ0hEGQ3uUWFDzBTVvMe+JTqlIG+zm4AjXMuHuQvr" +
            "FcdbBTa2j4edIcIikCXtyFXwtDUgCyLiJAItE/p17BDgsqNh41s1W0aLDjDRkx0a4Q6phbYNTncdA3aucBj4SdXgaWyyNEAyWmWS" +
            "nI7SiyPEcz6qp/IJTc/VvAFatwpUvj8TTzMA/WywKzyFSx26GmKyiXN5k0NYZVgdIvWIbk+lEbpwO3ErQuL5Ae9jw1ZY6KoBL9zB" +
            "RW9igciaWSG2/XGWeCzPHrXlWUeoIwuEB8IIyWLVyzqs+NN86KqWSLZpqoLQpnTnQSwz8ycy1rM/HPdRlY4upF1P1zP9/1NDXiq/" +
            "dGZhEIQiTi46p4NauZI/LABsrJbfDY464j8pS6ocUME6ezxBQ8PhVZH+nj5EgXPBusD2W3qeT+IJTEHT1UGMGSX2St7XM4kzSVx+" +
            "S4E5OAIg2S/YRM67NS5i8SLjMUewXKstrp7GPc+PvaK6PEZDykS9N772MCbM2DNrTlPedy8dGpbq4CgwLu5zM2ISh+VUwsAPIJAx" +
            "sM4E9DzFw2Hwyso3Op+YPhGt6V+33wsbTv5brnGEQEb8UmrAikT/lTKp+zwUAouzaH55qWX9mBin3tDkH+tERR6+T9fzd5UJvepV" +
            "U+5axidWBn10MP9w/jbI1q+LuhTxXhAaJ9FxldKFezVOqdkRev1zLr0vAusZHPabFa2A8DWm6nRVuluA6n9mesA3TP0KTIA59/de" +
            "VvQM4IUha7gxLxgvIvgvrHEdK16QG2x5Mkwtl603DRMm+JHFw7Sy96qFtX9Jh9jY9t5tDZ6ifKQJ95nGjS7q3zRUCE1SaGh0JWht" +
            "/wzgU9zM02/6Dgv0bHWOvu42iznOHhpAQAobF4X3pKJfzg91MjvhOKDv/0jqwgj4uARenQt/eQPvSS6Iagsfb/fIXdLVm+gdex02" +
            "AKXcnixzwXnYY73ULcGRQp6OxIk7QFpTCc8GlvAL4S9KucYiGTHy+rlFLYsC+kZLNsyEfR2rvMEKMhnIyu3Bg88WSLheNDc4Hecp" +
            "cuIM+kyXX50mkDjOUAS7/Ph6hiSF6ndP3kGpgW/+mSeNymQPPBHea6QxNpmWatIEPwTEBGCCrRVp9/d40+cRvc2HUGj5XY1JsKAY" +
            "kmurRTI1+a6Zumunagz5cfGQErWJ1WCOjbY1SXdgqTIANxhk42Iaw7oVBP+FiCfyqo0/xGblHFDGMOy80AJ5tGwevzTfxEq/dIrR" +
            "62x8JSPlFlS5msYBmW/9ReUjAGyiur7a34TysREiUyr+yba7p5P83LfVVrm7uD7XLiR57V/Q0t5EcwV3Is7Nc9GpvbzqQOUAVEHL" +
            "OPiIHHHj9cZ6MN9e1qzCO8dVxMO7V1BWhQM4hyACn2xSmCXWHPtQYgNl8PXstYyp4Ck1yCGlZxFwk9Y7766uOyITYVns49zgNinm" +
            "ngGbe0Om7bqR6LBCOSpdWrwQd0lbEFBhC604F3zi5lPb3jWDoikoqjmOA6Bt9mSGDVMJintt74QPanWbHsxU88VJ/x6RQTCRcLqe" +
            "RuO1GvrzvckBfzaczq48dxkP0Wq0XGt2bV7swTQl/bnjqIQS+L7YsfxNMajla50hXVXnF8Qd/BcwC2zCom38ndMobKxvlQ2ytKUz" +
            "vJsubxUlMqSV/TRmcab9z6urftM7BeevieFk4C+BXccewM+DPEuty+NRO+XpflrFgygZ3dDDbdM2YwbdhWjZbFW+E/jKaWqW4MFX" +
            "brdHpqdJDVIHz9ONhtF1UJprERadA8YUD7plZ+SOdoEzE9VTaN+sBIzCIAUny9T6ccYai55G6pljJGjlWPWb/cleJpvVg9BydyzK" +
            "eOSEjbbw+yOwd4I8rE3F7eGihddkDWOvgSnMf6wbwsf6aTTK0KDnAt/T0qViNFcBBL1H6S6prAuai1ATwXVpcKHYM/eI5Pz755rJ" +
            "J0KsIQFmLANnCqoS1AUzpKxDO7RCEviZ4wVqDqW41PqeoIUxRwLySz1KUZX+SrvHRbtzm7RePVum1tSIcQqjH++kMRvih7ahhST/" +
            "WA4IPXLOlAIJRwreVBLgzkK4KXRCVG1zdSnGSAuXuPNtiTCCBUEGCSqGSIb3DQEHAaCCBTIEggUuMIIFKjCCBSYGCyqGSIb3DQEM" +
            "CgECoIIE7jCCBOowHAYKKoZIhvcNAQwBAzAOBAievxgnUUtEfwICCAAEggTIE8FLBugmAyPcyNYHmRrt7H+e8fmFi3GqbwOLTMu5" +
            "IB8qHn15T3MKDzTDQXyxLMlPsVYFIXeLfuaNNDYOL9Q9XruJ6aR9kOb3epGWKNRs4w5Q4S4SNvQ/5jTVBe9vnCNc2MT1R0JhM1jV" +
            "tk5n2qqAYkHs/AT02rzdbKoGT3Qp/XdLv4KgAwQINSRUUpWExs3VhJn+8icjdo67ds8u6KbDqFA1HrBpJmcRW/n/gOJlG367hq66" +
            "rKvVXllZSUYsBRPCa/CBqzKvD5/vk+sj8ksZ8x2bDOiNbN6yyK0nwfXT030IA9d1KB+gH0j6OiaIDLhzBW5tEqRFqOY33auSAMqi" +
            "P6TbYFROrkFX6VXnUsgZWu9yLYECc2hMFlkdwbMhrcAwJKZBHD+HGx+CtpnJIMfL47gCaumlwCN6als5E7dhUrn9DwArwslUCTY8" +
            "ZuT3t+3I9KkjtMxv+w/gEE9/Mi49Ct03e3ZafQoYivnsmlZY+bjsann9cJ3N3cBRT4y6aK3lvtAM+jiTUOPAWuqnLFspgzoGRaH/" +
            "SAuEFTVxEzFexGIzTmA47ubCX/Xp4A6FdSFPkwdB28k5V+Dmp42HkRgrKAil1BSyDQDmDNtOTyNekLSK8rLOLdGsT6Qax1r2hLiD" +
            "3bO4J+rsSmpkKoVZv1eflYfjoTkLvlHgYt9nHNDr44M4hFiibdjxHHTex6uMioxT/NKbaDCJVUxl2ZNF53RF4+KwN6/p3L9hvoFt" +
            "kWY+LiQN36mmfC172L/cL89OZ2CmnJ8V3CvPIp9gCg+SuvxPC7qsHmM7iKy2pnayJJOC5GF1AvjEyUQxVyeyJblBqpuOVtoxQCo8" +
            "KKACDgZw4l/ITHwD7rHTCRvR7xp6cKvhDD2+SSWyb3MfAWihK7B8uoGX7uZb+u+Rl+qkx3EKvz0Q/dMLiBJoik8OxRfoFj2hmaJv" +
            "/gmmld2BLH7Nj39PnoZSP2+VngnsDmDueNbP2M9UB4OhzSYUSurGnfPwDEuPo6hkD1dNLAzBq+sa/VDEFbMFH90nfLmJ040C/Qy8" +
            "7Wd1/brDI2FnLapD9nLY4EeCM4VWxry7PkA8im/8WAwr8s+tnqbUW71cd8E7+7ThdeuKlHftwZwTU1pWiMV7yjZzlzCPUTiL40RO" +
            "t2PeJ/LJ6or4D1a65KDWMTBRcdYkgqxmkrCmAIPXA5kBbAe422L4jModF8ElPVPQKLZynVTketwjaRjP/EGDPLZ/zaLa1pKJfZhs" +
            "5Xi3139AMYoC04UKgoDhIiEaNVwQxqxgF08ncuE63/mIiTYh+qwp9ZJ8a/kyBEu/wVYS+XPbRnoyU+L7vWGtfruRcCfMcMohzYEJ" +
            "goK2qUllpBkDdeoVHr1b/DYrvzt8AlC3sAd5UXGIz48MQAxcBmvNjLYJKooMc5AGB+ptGPb/tZhRovm+UL+9OuJzLTxei3uu1vWc" +
            "oCq2AKWk5gpSL0kmEBlBm7Zj12PgO5Dn5NkHyKfV5cZ0n12esHmOJeAxllArS2csKSycVTR/GDO+F+DSTOnETZN5R6igj5byZ9VT" +
            "PEeaH6dW1/dFq+O62F6lJkjXhGRVy0jO6DljY02S0wAG5Wdea8c7xl6FjBXet8wabBN0f7VpE+yLHr8xh3snBkhcAZFCMSUwIwYJ" +
            "KoZIhvcNAQkVMRYEFL54R4i59GUJvLyFwQ1fLKDlcK00MDEwITAJBgUrDgMCGgUABBQPpAPsfG6lipwGWOhqbzhvE2fLZgQIerWQ" +
            "8MNQ3ecCAggA"
        const val DETACHED =
            "MIII9QYJKoZIhvcNAQcCoIII5jCCCOICAQExDTALBglghkgBZQMEAgEwCwYJKoZIhvcNAQcBoIIGgTCCAxUwggH9oAMCAQICFDoy" +
            "/EcQzpQUOi/7nM6qKKJkVuKDMA0GCSqGSIb3DQEBCwUAMBIxEDAOBgNVBAMMB1Rlc3QgQ0EwHhcNMjYxMTIxMTk0NzMyWhcNMjYx" +
            "MjIxMTk0NzMyWjASMRAwDgYDVQQDDAdUZXN0IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvK6eMY6Mu8WZJszV" +
            "NVGQ8T27vKgonRTi9+CFosHKMsQfQcQySQbvUM0UMvA6FqzNgK2gMxm9aIpG+bgrWK5mgit8rns3KZ7RWJnbok4ghmthVSCWjCna" +
            "TLJCI4vLGmgKNHCamzjdF1vh+ZgvIZTCi1s+l3hOVyo8u8xdPpWpFYv3UyyyNtCTxcbTm3ormY4dLy3fbHuTBOU1AOpW5mbacJk+" +
            "wJFToXgA/apYT0YOXErWG/9JJE4Jk8kjdJx8o8ijd8nHT01YpQXDB0aAdC51oDJ8A4UcoyqM5J6exVgxboZFAuyREBqKcd35Ch9b" +
            "hIyKi1bWsm7QFxJCrwJxMj6cXQIDAQABo2MwYTAdBgNVHQ4EFgQUyzfEduslDclTSsgLuWFnPzJcN9YwHwYDVR0jBBgwFoAUyzfE" +
            "duslDclTSsgLuWFnPzJcN9YwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAgQwDQYJKoZIhvcNAQELBQADggEBAJKZ+VF+" +
            "14C/REJyiIChFmaOEPUdQ4kvU+LdRvjt61tprKbtbIcinYDvypDYvh67fC7+WOEs2LVwlotCxFwaDiZwGh6p9iGx7x9G4h7y7go1" +
            "rbRIEjuYYuNM6DvCGgwAzVSD1m97LJa/i76wN6cdjpDErVmTNldntl6LQj2ZUdjItYzwRwVou0sFVM4iqEVcLFVBXZDhzCJ8jF6U" +
            "TdLfmKjnLwhXHlVhnkMoRkaMCqfsLDwUMw18bqgzD8YKwqOH85vvPmQ+7vpAllCQZaoVMGIuw12qjkIkZWdL3e8HoFwcyqnsJyhj" +
            "Ywa7/HaYDxHuHC4DGyPOPL8MKMCytabkusMwggNkMIICTKADAgECAhQj4EUjsGveCooZh1HP9AFsuEakeDANBgkqhkiG9w0BAQsF" +
            "ADASMRAwDgYDVQQDDAdUZXN0IENBMB4XDTI2MTEyMTE5NDczMloXDTI2MTIyMTE5NDczMlowMjEOMAwGA1UEAwwFQWxpY2UxIDAe" +
            "BgkqhkiG9w0BCQEWEWFsaWNlQGV4YW1wbGUub3JnMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuFXw1Kqd0XBTmeGL" +
            "kHB1QAO3TAhgs1Em8YDrkV0sRW+Ekj9pmFJ1pWDJtqm0VQIsyaqrPcJ7oo5OdWvR/2k7PSmq56wcvVjuXbgYvUhZ2DkKRNGgdgXl" +
            "Il/fBPfOJI2NURGAS7j1Dx1YDC67ipNHQx3VY5UFIul2IdbrqfZa2S4HqyIokw6tCvbLZjbOl5uRC5n87BXeAWgDfPJP+TbE5/oT" +
            "o7qK3M5UeulXo83UbySSjubWDqYXuEd8/NtBMDPpIhYND1WiNN2OvYJvQnLWG7Et3efy7TYkJQhzhoJi7voY6zUKQogm1tsTSPpD" +
            "nbtnCb5oNcfpaPx637GqAgToAwIDAQABo4GRMIGOMAkGA1UdEwQCMAAwDgYDVR0PAQH/BAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUF" +
            "BwMEMBwGA1UdEQQVMBOBEWFsaWNlQGV4YW1wbGUub3JnMB0GA1UdDgQWBBSyNj+e72Ap31nqB3VrldDLiUVT/zAfBgNVHSMEGDAW" +
            "gBTLN8R26yUNyVNKyAu5YWc/Mlw31jANBgkqhkiG9w0BAQsFAAOCAQEAnQ1gqj5eGoVY66fpFb96mv5kXm5gjoI+aU7AnurOoCwQ" +
            "zrDwUl2G45mUsyNienJ2I0kz/AcC0yvu2vFYT0JgbZsym/GueLZu8cHMmLT5U0gXeJfTEVXUMehi9cMdzmx3sgkJAF4XSI/1sR0T" +
            "CfQNC3rXKs/gN263LUZyipDcfy6w4bNKhbhKF00pynfqnm6dR7UgvhZeVhicNSFZSj6CcTXKKkBqCobaYaUYj3xTyhhW/bE4iTHv" +
            "s71BjTPwdjBr41Nfv0XBPPwV1wTMgZAnr2cFKc5sdfNWV+Sir5Qc2d0doqVBO0w67dvzO2TMGczy1YsvZVKHmhLLLujObsYdpjGC" +
            "AjowggI2AgEBMCowEjEQMA4GA1UEAwwHVGVzdCBDQQIUI+BFI7Br3gqKGYdRz/QBbLhGpHgwCwYJYIZIAWUDBAIBoIHkMBgGCSqG" +
            "SIb3DQEJAzELBgkqhkiG9w0BBwEwHAYJKoZIhvcNAQkFMQ8XDTI2MTEyMTE5NDczMlowLwYJKoZIhvcNAQkEMSIEIH8ECIPnR+vb" +
            "8qSsCSOX8TkxUOlYJMWMAUAFncIhCtCFMHkGCSqGSIb3DQEJDzFsMGowCwYJYIZIAWUDBAEqMAsGCWCGSAFlAwQBFjALBglghkgB" +
            "ZQMEAQIwCgYIKoZIhvcNAwcwDgYIKoZIhvcNAwICAgCAMA0GCCqGSIb3DQMCAgFAMAcGBSsOAwIHMA0GCCqGSIb3DQMCAgEoMA0G" +
            "CSqGSIb3DQEBAQUABIIBAAEnNfaUwija+Pa7rUn91LVLSNy8COErhooAurOrSaEokwz/BNXSkOklzgc5VNuR9p7JBUKMkfadfwi8" +
            "L2IdC9djSYvWDhBEnV6Oz7TITGT6G+xy7Gq8eao6MceL0kkMpsAsAvQsTAx4nUo3VYgkxaOmw8xca78GZ9QfBOvI7iczpV7aSApO" +
            "GzO4THNSUt29ikG+zi8Wgk2GCJE5Z3y3x864X3KOQ6tAIrDknq83NVAZOOTOyN3LyLim5FQj01YZmUne5RYasC6qCVqMUX5Sm3tT" +
            "LwC4YyA9dwdEoq9YvZFtewkvMzltpyWxjSuL13uxTmXANxbNxrXG7gulPNycDik="
        const val OPAQUE =
            "MIIGHwYJKoZIhvcNAQcCoIIGEDCCBgwCAQExDTALBglghkgBZQMEAgEwTgYJKoZIhvcNAQcBoEEEP0NvbnRlbnQtVHlwZTogdGV4" +
            "dC9wbGFpbjsgY2hhcnNldD11dGYtOA0KDQpIZWxsbyBmcm9tIE9wZW5TU0wNCqCCA2gwggNkMIICTKADAgECAhQj4EUjsGveCooZ" +
            "h1HP9AFsuEakeDANBgkqhkiG9w0BAQsFADASMRAwDgYDVQQDDAdUZXN0IENBMB4XDTI2MTEyMTE5NDczMloXDTI2MTIyMTE5NDcz" +
            "MlowMjEOMAwGA1UEAwwFQWxpY2UxIDAeBgkqhkiG9w0BCQEWEWFsaWNlQGV4YW1wbGUub3JnMIIBIjANBgkqhkiG9w0BAQEFAAOC" +
            "AQ8AMIIBCgKCAQEAuFXw1Kqd0XBTmeGLkHB1QAO3TAhgs1Em8YDrkV0sRW+Ekj9pmFJ1pWDJtqm0VQIsyaqrPcJ7oo5OdWvR/2k7" +
            "PSmq56wcvVjuXbgYvUhZ2DkKRNGgdgXlIl/fBPfOJI2NURGAS7j1Dx1YDC67ipNHQx3VY5UFIul2IdbrqfZa2S4HqyIokw6tCvbL" +
            "ZjbOl5uRC5n87BXeAWgDfPJP+TbE5/oTo7qK3M5UeulXo83UbySSjubWDqYXuEd8/NtBMDPpIhYND1WiNN2OvYJvQnLWG7Et3efy" +
            "7TYkJQhzhoJi7voY6zUKQogm1tsTSPpDnbtnCb5oNcfpaPx637GqAgToAwIDAQABo4GRMIGOMAkGA1UdEwQCMAAwDgYDVR0PAQH/" +
            "BAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMEMBwGA1UdEQQVMBOBEWFsaWNlQGV4YW1wbGUub3JnMB0GA1UdDgQWBBSyNj+e72Ap" +
            "31nqB3VrldDLiUVT/zAfBgNVHSMEGDAWgBTLN8R26yUNyVNKyAu5YWc/Mlw31jANBgkqhkiG9w0BAQsFAAOCAQEAnQ1gqj5eGoVY" +
            "66fpFb96mv5kXm5gjoI+aU7AnurOoCwQzrDwUl2G45mUsyNienJ2I0kz/AcC0yvu2vFYT0JgbZsym/GueLZu8cHMmLT5U0gXeJfT" +
            "EVXUMehi9cMdzmx3sgkJAF4XSI/1sR0TCfQNC3rXKs/gN263LUZyipDcfy6w4bNKhbhKF00pynfqnm6dR7UgvhZeVhicNSFZSj6C" +
            "cTXKKkBqCobaYaUYj3xTyhhW/bE4iTHvs71BjTPwdjBr41Nfv0XBPPwV1wTMgZAnr2cFKc5sdfNWV+Sir5Qc2d0doqVBO0w67dvz" +
            "O2TMGczy1YsvZVKHmhLLLujObsYdpjGCAjowggI2AgEBMCowEjEQMA4GA1UEAwwHVGVzdCBDQQIUI+BFI7Br3gqKGYdRz/QBbLhG" +
            "pHgwCwYJYIZIAWUDBAIBoIHkMBgGCSqGSIb3DQEJAzELBgkqhkiG9w0BBwEwHAYJKoZIhvcNAQkFMQ8XDTI2MTEyMTE5NDczMlow" +
            "LwYJKoZIhvcNAQkEMSIEIH8ECIPnR+vb8qSsCSOX8TkxUOlYJMWMAUAFncIhCtCFMHkGCSqGSIb3DQEJDzFsMGowCwYJYIZIAWUD" +
            "BAEqMAsGCWCGSAFlAwQBFjALBglghkgBZQMEAQIwCgYIKoZIhvcNAwcwDgYIKoZIhvcNAwICAgCAMA0GCCqGSIb3DQMCAgFAMAcG" +
            "BSsOAwIHMA0GCCqGSIb3DQMCAgEoMA0GCSqGSIb3DQEBAQUABIIBAAEnNfaUwija+Pa7rUn91LVLSNy8COErhooAurOrSaEokwz/" +
            "BNXSkOklzgc5VNuR9p7JBUKMkfadfwi8L2IdC9djSYvWDhBEnV6Oz7TITGT6G+xy7Gq8eao6MceL0kkMpsAsAvQsTAx4nUo3VYgk" +
            "xaOmw8xca78GZ9QfBOvI7iczpV7aSApOGzO4THNSUt29ikG+zi8Wgk2GCJE5Z3y3x864X3KOQ6tAIrDknq83NVAZOOTOyN3LyLim" +
            "5FQj01YZmUne5RYasC6qCVqMUX5Sm3tTLwC4YyA9dwdEoq9YvZFtewkvMzltpyWxjSuL13uxTmXANxbNxrXG7gulPNycDik="
        const val ENCRYPTED_OAEP =
            "MIIB+QYJKoZIhvcNAQcDoIIB6jCCAeYCAQAxggFxMIIBbQIBADAqMBIxEDAOBgNVBAMMB1Rlc3QgQ0ECFCPgRSOwa94KihmHUc/0" +
            "AWy4RqR4MDgGCSqGSIb3DQEBBzAroA0wCwYJYIZIAWUDBAIBoRowGAYJKoZIhvcNAQEIMAsGCWCGSAFlAwQCAQSCAQCpMI0Vv5nz" +
            "2Jgw8A6XNef7Wm/DbWwKwoYm+4ZgmxDNHbCyPZulxYfCC3fLLWBtdaoiHiSodyXTleCL3KobWKboQvz8wchRBbz3vcZFeyGBcOiD" +
            "+e3TV0RTx4fUpu9Z6+ZnjxRIXvqPyNwncyvdZNJ9KiZjRdH7OvrlgcYfXpovwwBejzT35YK5YKRYinHP25eVIRO3gFnog4eWZArw" +
            "RJwGyHP4gZZ/DM3fEJ3+JffiHS5accasCBcwbCcWvzsWG6AdOQV4AafxgrtA/WI5gjnSY+Q7Bgjq5RhBr5pa4RkXetbGLJhnKWNh" +
            "LIzwd7pDz7+a+cobDpdDQUlQEkUg7pKCMGwGCSqGSIb3DQEHATAdBglghkgBZQMEAQIEEAYf6Iga436/P9Olgi59V9yAQJdPFaUD" +
            "RcKiM3xgdibJtkdCHeXSSkX1TZlZp3leioA5gVQbgVMJMJ7FbMJI4QvEsNs0M+Tax62OaCL7CoGGjf8="
    }
}
