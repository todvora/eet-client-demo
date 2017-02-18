package cz.tomasdvorak.eet.demo;

import cz.etrzby.xml.TrzbaDataType;
import cz.etrzby.xml.TrzbaType;
import cz.tomasdvorak.eet.client.EETClient;
import cz.tomasdvorak.eet.client.EETServiceFactory;
import cz.tomasdvorak.eet.client.config.CommunicationMode;
import cz.tomasdvorak.eet.client.config.EndpointType;
import cz.tomasdvorak.eet.client.dto.SubmitResult;
import cz.tomasdvorak.eet.client.exceptions.CommunicationException;
import cz.tomasdvorak.eet.client.exceptions.DataSigningException;
import cz.tomasdvorak.eet.client.exceptions.InvalidKeystoreException;
import cz.tomasdvorak.eet.client.persistence.RequestSerializer;
import cz.tomasdvorak.eet.client.security.ClientKey;
import cz.tomasdvorak.eet.client.security.ServerKey;
import cz.tomasdvorak.eet.client.utils.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.util.*;

public class Application {

    private static final Logger loggerPrinter = LogManager.getLogger("PRINTER");
    private static final Logger loggerDatabase = LogManager.getLogger("DATABASE");

    private static final Queue<String> failedReceipts = new LinkedList<String>(
            // one historical entry from, let's say, yesterday
            Collections.singletonList("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Trzba xmlns=\"http://fs.mfcr.cz/eet/schema/v3\"><Hlavicka uuid_zpravy=\"a70b8079-4546-4748-bab6-4bcb7f65d726\" dat_odesl=\"2017-02-07T18:03:08+01:00\" prvni_zaslani=\"true\" overeni=\"false\"/><Data dic_popl=\"CZ683555118\" id_provoz=\"243\" id_pokl=\"24/A-6/Brno_2\" porad_cis=\"#135433c/11/2016\" dat_trzby=\"2017-02-07T18:03:08+01:00\" celk_trzba=\"3264.00\" rezim=\"0\"/><KontrolniKody><pkp digest=\"SHA256\" cipher=\"RSA2048\" encoding=\"base64\">gZ5Ogn0S0dH3pmKcFvahICwBPh8V1XcXsNYJeRLgxclpHJgpC3L4UOO5N0R8RLorzx1GxETuyrxTQTziBNLSL9eZqoBkIzzkkmqxKUd5nFFZJPKVVVCZDEo4nU//Ubh1dlM8fzPuvFD0B072cN17B/WkgzqXOcxB/GiFPfWGYe98AElk0x7bGHrfR7i59WsoSzIQAx6maymyo6+oG+cTz/trNqWohD91Y3SkSpBy5F6xIGNkNh0f4IGuoLLgzDXospVRkVrunEfEyhwOcz0XaRt0Hhbv3m3FBW9PActlxZ9TT6pKz8vxH3iNkycQoRxuW9CLmTPh3lZgbtkLDVRYYw==</pkp><bkp digest=\"SHA1\" encoding=\"base16\">1BD99461-8DAA6103-0BE67119-C01A9524-271F70A9</bkp></KontrolniKody></Trzba>")
    );

    public static void main(String[] args) throws InvalidKeystoreException, DataSigningException {

        // get the client key from classpath
        final ClientKey clientKey = ClientKey.fromInputStream(Application.class.getResourceAsStream("/keys/CZ683555118.p12"), "eet");

        // if you want to load client key from a file on a filesystem, use following method:
        // final ClientKey clientKey = ClientKey.fromFile("/home/maxmustermann/eet/keys/CZ683555118.p12", "eet");

        // get the server key, trusting playground and production server certificates issued by I.CA, embedded in this client
        final ServerKey serverKey = ServerKey.trustingEmbeddedCertificates();

        // if you want to provide your own certificates for validation of responses, use
        // final ServerKey serverKey = ServerKey.fromFile("/home/maxmustermann/eet/certs/qica.der");

        // get the eet client service bound to this client key
        final EETClient service = EETServiceFactory.getInstance(clientKey, serverKey);

        // prepare receipt data
        final TrzbaDataType receipt = new TrzbaDataType()
                .withDicPopl("CZ683555118")
                .withIdProvoz(243)
                .withIdPokl("24/A-6/Brno_2")
                .withPoradCis("#135433c/11/2016")
                .withDatTrzby(new Date())
                // .withXXX() - see all the .withXXX methods for setting all possible fields
                .withCelkTrzba(new BigDecimal("3264"));

        // prepare request for first send, generate UUID of message, submission date, BKP and PKP codes
        final TrzbaType request = service.prepareFirstRequest(receipt, CommunicationMode.REAL);

        try {
            // try to submit the receipt
            final SubmitResult response = service.sendSync(request, EndpointType.PLAYGROUND);

            // everything went well, we have received FIK
            final String bkp = response.getBKP();
            final String fik = response.getFik();

            // print standard receipt
            printReceiptOnline(receipt, bkp, fik);
        } catch (CommunicationException e) {
            // failed, should be repeated later (using cron / scheduled task or some other planing)!

            // first persist for later repeat
            saveToDatabaseForLaterResend(request, EndpointType.PLAYGROUND);

            // get receipt data to be printed on an paper receipt
            final String bkp = e.getBKP();
            final String pkp = e.getPKP();

            printReceiptOffline(receipt, bkp, pkp);
        }

        // finally, resend all failed requests
        resendAllFailedRequestsNow(service);


    }

    private static void resendAllFailedRequestsNow(final EETClient service) throws DataSigningException {
        // how many receipts do we have in outgoing queue (they have failed before)
        loggerDatabase.info("Receipts to resend: " + failedReceipts.size());

        String nextReceipt;
        while ((nextReceipt = failedReceipts.poll()) != null) {
            // deserialize request from string
            final TrzbaType request = RequestSerializer.fromString(nextReceipt);

            // let it re-generate the message UUID, send date and set first-submission flag to false
            final TrzbaType repeatedRequest = service.prepareRepeatedRequest(request);
            try {
                // try resend
                final SubmitResult result = service.sendSync(repeatedRequest, EndpointType.PLAYGROUND);
                // if we succeeded, we remove it from the resend queue
                failedReceipts.remove(nextReceipt);
                loggerDatabase.info("Succeeded to resend receipt " + result.getRequest().getData().getPoradCis() + ". Horray!");
            } catch (CommunicationException e) {
                // failed again, do not remove from the queue
                loggerDatabase.warn("Failed to resend receipt " + request.getData().getPoradCis() + ". Let's try it later again.");
            }
            loggerDatabase.info("Receipts to resend: " + failedReceipts.size());
        }


    }

    private static void saveToDatabaseForLaterResend(final TrzbaType request, final EndpointType endpoint) {

        // convert request to String, it will be later correctly deserialized
        final String serializedRequest = RequestSerializer.toString(request);

        final boolean isOnlyTestRequest = request.getHlavicka().isOvereni();
        final boolean shouldBeRepeated = !isOnlyTestRequest && EndpointType.PRODUCTION == endpoint;
        // only requests to the production endpoint, which aren't test should be repeated later
        // if(shouldBeRepeated) {
            loggerDatabase.info("*********");
            loggerDatabase.info("Persisting request: " + serializedRequest);
            loggerDatabase.info("*********");
            failedReceipts.add(serializedRequest);
        // }
    }

    private static void printReceiptOffline(final TrzbaDataType receipt, final String bkp, final String pkp) {
        loggerPrinter.info("*********");
        loggerPrinter.info("DIC: " + receipt.getDicPopl());
        loggerPrinter.info("Total amount: " + NumberUtils.format(receipt.getCelkTrzba()));
        loggerPrinter.info("Registered in OFFLINE mode.");
        loggerPrinter.info("BKP: " + bkp);
        loggerPrinter.info("PKP: " + pkp);
        loggerPrinter.info("*********");
    }

    private static void printReceiptOnline(final TrzbaDataType receipt, final String bkp, final String fik) {
        loggerPrinter.info("*********");
        loggerPrinter.info("DIC: " + receipt.getDicPopl());
        loggerPrinter.info("Total amount: " + NumberUtils.format(receipt.getCelkTrzba()));
        loggerPrinter.info("Registered in ONLINE mode.");
        loggerPrinter.info("FIK: " + fik);
        loggerPrinter.info("BKP: " + bkp);
        loggerPrinter.info("*********");
    }
}
