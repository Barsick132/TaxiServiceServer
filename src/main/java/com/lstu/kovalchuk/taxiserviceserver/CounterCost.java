/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.lstu.kovalchuk.taxiserviceserver;

import com.google.gson.Gson;
import com.lstu.kovalchuk.taxiserviceserver.mapapi.RespCounterCost;
import com.lstu.kovalchuk.taxiserviceserver.mapapi.RouteResponse;
import com.lstu.kovalchuk.taxiserviceserver.mapapi.Route;
import okhttp3.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

// [START example]
@SuppressWarnings("serial")
public class CounterCost extends HttpServlet {

    private static final String GOOGLE_MAPS_WEB_KEY = "AIzaSyCG1XBUe97ygn6nN9zCy-qG3VoiXbC68Bk";
    private static final String STATUS_FAIL = "FAIL";
    private static final String STATUS_OK = "OK";

    private Gson g = new Gson();
    private String strResp;

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PrintWriter out = resp.getWriter();

        String position = req.getParameter("position");
        String destination = req.getParameter("destination");
        String points = req.getParameter("points");

        if (position == null || destination == null) {
            RespCounterCost respCounterCost = new RespCounterCost(STATUS_FAIL);
            strResp = g.toJson(respCounterCost);
            out.println(strResp);
            return;
        }

        CountDownLatch countDownLatch = new CountDownLatch(1);
        if(points!=null && points.equals("true")){
            GetRoute(position, destination, true, countDownLatch);
        }else {
            GetRoute(position, destination, false, countDownLatch);
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            if(strResp==null) {
                RespCounterCost respCounterCost = new RespCounterCost(STATUS_FAIL);
                strResp = g.toJson(respCounterCost);
            }
        }

        /*
        Firestore db = FirestoreClient.getFirestore();
        Map<String, Object> test = new HashMap<>();
        test.put("geoPointPos", new GeoPoint(Double.parseDouble(position.split(",",2)[0]),
                Double.parseDouble(position.split(",")[1])));
        test.put("geoPointDes", new GeoPoint(Double.parseDouble(destination.split(",",2)[0]),
                Double.parseDouble(destination.split(",")[1])));
        db.collection("test").document().set(test);

        */

        out.println(strResp);
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

    private void GetRoute(String position, String destination, final boolean isPoints, final CountDownLatch countDownLatch) {
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
                RespCounterCost respCounterCost = new RespCounterCost(STATUS_FAIL);
                strResp = g.toJson(respCounterCost);
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
                        if(!isPoints) {
                            Double approxCost = (double) 50;

                            approxCost += ((minTimeRoute.getLegs().get(0).getDuration().getValue() / (double) 60) * 7) +
                                    ((minTimeRoute.getLegs().get(0).getDistance().getValue() / (double) 1000) * 7);
                            approxCost = Math.ceil(approxCost);

                            RespCounterCost respCounterCost = new RespCounterCost(STATUS_OK,
                                    approxCost.intValue(),
                                    minTimeRoute.getLegs().get(0).getDuration().getValue(),
                                    minTimeRoute.getLegs().get(0).getDistance().getValue());

                            strResp = g.toJson(respCounterCost);
                        }else {
                            RespCounterCost respCounterCost = new RespCounterCost(STATUS_OK,
                                    minTimeRoute.getOverviewPolyline().getPoints());
                            strResp = g.toJson(respCounterCost);
                        }
                    }else {
                        RespCounterCost respCounterCost = new RespCounterCost(STATUS_FAIL);
                        strResp = g.toJson(respCounterCost);
                    }
                    countDownLatch.countDown();
                } catch (Exception ex) {
                    RespCounterCost respCounterCost = new RespCounterCost(STATUS_FAIL);
                    strResp = g.toJson(respCounterCost);
                    countDownLatch.countDown();
                }
            }
        });
    }
}
