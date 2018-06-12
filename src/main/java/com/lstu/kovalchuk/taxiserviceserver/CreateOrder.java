package com.lstu.kovalchuk.taxiserviceserver;

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.GeoPoint;
import com.google.cloud.firestore.WriteResult;
import com.google.code.geocoder.Geocoder;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import com.google.gson.Gson;
import com.lstu.kovalchuk.taxiserviceserver.mapapi.*;
import com.lstu.kovalchuk.taxiserviceserver.mapapi.Route;
import okhttp3.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("serial")
public class CreateOrder extends HttpServlet {

    private static final String TAG = "CreateOrder";
    private static final String GOOGLE_MAPS_WEB_KEY = "AIzaSyCG1XBUe97ygn6nN9zCy-qG3VoiXbC68Bk";
    private static final String STATUS_FAIL = "FAIL";
    private static final String STATUS_OK = "OK";

    private Gson g = new Gson();
    private String strResp;
    private Order order;

    @Override
    public void init() throws ServletException {
        FileInputStream serviceAccount =
                null;
        try {
            serviceAccount = new FileInputStream("WEB-INF/taxiserviceproject-92fe6-firebase-adminsdk-ltsx2-6017ec4e51.json");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        FirebaseOptions options = null;
        try {
            options = new FirebaseOptions.Builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .setDatabaseUrl("https://taxiserviceproject-92fe6.firebaseio.com")
                    .build();
        } catch (IOException e) {
            e.printStackTrace();
        }

        FirebaseApp.initializeApp(options);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        PrintWriter out = resp.getWriter();

        try {
            String clientUID = req.getParameter("clientUID");
            String whenceAddress = req.getParameter("whenceAddress");
            String whenceGeoPoint = req.getParameter("whenceGeoPoint");
            String whereAddress = req.getParameter("whereAddress");
            String whereGeoPoint = req.getParameter("whereGeoPoint");
            String cashlessPay = req.getParameter("cashlessPay");
            String comment = req.getParameter("comment");

            if (clientUID == null || whenceAddress == null || whenceGeoPoint == null ||
                    whereAddress == null || whereGeoPoint == null || cashlessPay == null) {
                responseFail(out);
                return;
            }

            Firestore db = FirestoreClient.getFirestore();
            String orderID = db.collection("orders").document().getId();

            GeoPoint whenceGP = new GeoPoint(Double.parseDouble(whenceGeoPoint.split(",", 2)[0]),
                    Double.parseDouble(whenceGeoPoint.split(",", 2)[1]));
            GeoPoint whereGP = new GeoPoint(Double.parseDouble(whereGeoPoint.split(",", 2)[0]),
                    Double.parseDouble(whereGeoPoint.split(",", 2)[1]));
            boolean bCashlessPay = Boolean.parseBoolean(cashlessPay);

            CountDownLatch countDownLatch = new CountDownLatch(1);
            GetRoute(whenceGeoPoint, whereGeoPoint, countDownLatch);
            countDownLatch.await();

            if (order == null) {
                responseFail(out);
                return;
            }

            order.assembleOrder(orderID, clientUID, whenceGP,
                    whenceAddress, whereGP, whereAddress,
                    bCashlessPay, comment);

            ApiFuture<WriteResult> result = db.collection("orders").document(orderID)
                    .set(order);

            System.out.println("TimeUpdate: " + result.get().getUpdateTime());

            RespCreateOrder respCreateOrder = new RespCreateOrder(STATUS_OK, orderID);
            strResp = g.toJson(respCreateOrder);
            out.println(strResp);
        } catch (Exception ex) {
            responseFail(out);
        }
    }

    private static class GetQuery {
        OkHttpClient client = new OkHttpClient();

        void run(String url, Callback callback) {
            Request request = new Request.Builder()
                    .url(url)
                    .build();

            client.newCall(request).enqueue(callback);
        }
    }

    private void GetRoute(String position, String destination, final CountDownLatch countDownLatch) {
        GetQuery query = new GetQuery();
        String url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=" + position +
                "&destination=" + destination +
                "&sensor=true" +
                "&language=ru" +
                "&key=" + GOOGLE_MAPS_WEB_KEY;

        query.run(url, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                order = null;
                countDownLatch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String jsonString = response.body().string();
                    RouteResponse routeResponse = g.fromJson(jsonString, RouteResponse.class);

                    if (routeResponse.getStatus().equals("OK")) {
                        Route minTimeRoute = routeResponse.getRoutes().get(0);
                        for (Route route : routeResponse.getRoutes()) {
                            if (route.getLegs().get(0).getDuration().getValue() < minTimeRoute.getLegs().get(0).getDuration().getValue()) {
                                minTimeRoute = route;
                            }
                        }

                        Double approxCost = (double) 50;

                        approxCost += ((minTimeRoute.getLegs().get(0).getDuration().getValue() / (double) 60) * 7) +
                                ((minTimeRoute.getLegs().get(0).getDistance().getValue() / (double) 1000) * 7);
                        approxCost = Math.ceil(approxCost);

                        order = new Order();
                        order.setApproxCost(approxCost.intValue());
                        order.setApproxTimeToDest(minTimeRoute.getLegs().get(0).getDuration().getValue());
                        order.setApproxDistanceToDest(minTimeRoute.getLegs().get(0).getDistance().getValue());
                    } else {
                        order = null;
                    }
                    countDownLatch.countDown();
                } catch (Exception ex) {
                    order = null;
                    countDownLatch.countDown();
                }
            }
        });
    }

    private void responseFail(PrintWriter out) {
        RespCreateOrder respCreateOrder = new RespCreateOrder(STATUS_FAIL);
        strResp = g.toJson(respCreateOrder);
        out.println(strResp);
    }
}
