import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Paths;
import java.util.HashMap;

import static spark.Spark.*;

public class Server {
    private static Gson gson = new Gson();

    static class CreatePaymentRequest {
        @SerializedName("paymentMethodType")
        String paymentMethodType;

        @SerializedName("currency")
        String currency;

        @SerializedName("amount")
        String amount;

        public String getPaymentMethodType() {
            return paymentMethodType;
        }

        public String getCurrency() {
            return currency;
        }

        public String getAmount() {
            return amount;
        }
    }

    static class ConfigResponse {
        private String publishableKey;

        public ConfigResponse(String publishableKey) {
            this.publishableKey = publishableKey;
        }
    }

    static class FailureResponse {
        private HashMap<String, String> error;

        public FailureResponse(String message) {
            this.error = new HashMap<String, String>();
            this.error.put("message", message);
        }
    }

    static class CreatePaymentResponse {
        private String clientSecret;

        public CreatePaymentResponse(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }

    public static void main(String[] args) {

        Dotenv dotenv = Dotenv.load();
        int i = Integer.parseInt((dotenv.get("PORT")));
        port(i);
        Stripe.apiKey = dotenv.get("STRIPE_SECRET_KEY");

        // For sample support and debugging, not required for production:
        Stripe.setAppInfo(
                "stripe-samples/accept-a-payment/custom-payment-flow",
                "0.0.1",
                "https://github.com/stripe-samples"
        );

        staticFiles.externalLocation(
                Paths.get(
                        Paths.get("").toAbsolutePath().toString(),
                        dotenv.get("STATIC_DIR")
                ).normalize().toString());

        get("/config", (request, response) -> {
            response.type("application/json");

            return gson.toJson(new ConfigResponse(dotenv.get("STRIPE_PUBLISHABLE_KEY")));
        });

        post("/create-payment-intent", (request, response) -> {
            response.type("application/json");

            CreatePaymentRequest postBody = gson.fromJson(request.body(), CreatePaymentRequest.class);

            PaymentIntentCreateParams.Builder paramsBuilder = new PaymentIntentCreateParams
                    .Builder()
                    .addPaymentMethodType(postBody.getPaymentMethodType())
                    .setCurrency(postBody.getCurrency())
                    .setAmount(Long.valueOf(postBody.getAmount()));

            System.out.println(postBody.getPaymentMethodType());
            if(postBody.getPaymentMethodType().equals("acss_debit")) {
                paramsBuilder.setPaymentMethodOptions(
                        PaymentIntentCreateParams.PaymentMethodOptions
                                .builder()
                                .setAcssDebit(PaymentIntentCreateParams
                                        .PaymentMethodOptions
                                        .AcssDebit
                                        .builder()
                                        .setMandateOptions(PaymentIntentCreateParams
                                                .PaymentMethodOptions
                                                .AcssDebit
                                                .MandateOptions
                                                .builder()
                                                .setPaymentSchedule(PaymentIntentCreateParams.PaymentMethodOptions.AcssDebit.MandateOptions.PaymentSchedule.SPORADIC)
                                                .setTransactionType(PaymentIntentCreateParams.PaymentMethodOptions.AcssDebit.MandateOptions.TransactionType.PERSONAL)
                                                .build())
                                        .build())
                                .build());
            }

            PaymentIntentCreateParams createParams = paramsBuilder.build();

            try {
                PaymentIntent intent = PaymentIntent.create(createParams);

                return gson.toJson(new CreatePaymentResponse(intent.getClientSecret()));
            } catch(StripeException e) {
                response.status(400);
                return gson.toJson(new FailureResponse(e.getMessage()));
            } catch(Exception e) {
                response.status(500);
                return gson.toJson(e);
            }
        });

        post("/webhook", (request, response) -> {
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = dotenv.get("STRIPE_WEBHOOK_SECRET");

            Event event = null;

            try {
                event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
            } catch (SignatureVerificationException e) {
                // Invalid signature
                response.status(400);
                return "";
            }

            switch (event.getType()) {
                case "payment_intent.succeeded":
                    // Fulfill any orders, e-mail receipts, etc
                    // To cancel the payment you will need to issue a Refund
                    // (https://stripe.com/docs/api/refunds)
                    System.out.println("💰Payment received!");
                    break;
                case "payment_intent.payment_failed":
                    System.out.println("❌ Payment failed.");
                    break;
                default:
                    // Unexpected event type
                    response.status(400);
                    return "";
            }

            response.status(200);
            return "";
        });
    }


}
