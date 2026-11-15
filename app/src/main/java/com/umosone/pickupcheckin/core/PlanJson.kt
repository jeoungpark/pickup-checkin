package com.umosone.pickupcheckin.core

import org.json.JSONArray
import org.json.JSONObject

/** assets/plans 폴더의 JSON ↔ Plan. (12/3 전에는 서버의 "초대 링크로 만든 픽업"을 같은 형식으로 받는다.) */
object PlanJson {
    fun parse(text: String): Plan {
        val o = JSONObject(text)
        val stops = o.getJSONArray("stops").let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                Stop(
                    id = s.getString("id"), place = s.getString("place"),
                    lat = s.getDouble("lat"), lon = s.getDouble("lon"),
                    meetHint = s.optString("meetHint"),
                    type = if (s.optString("type").equals("DROPOFF", true)) StopType.DROPOFF else StopType.PICKUP,
                    riders = s.getJSONArray("riders").let { r ->
                        (0 until r.length()).map { j ->
                            val x = r.getJSONObject(j)
                            Rider(x.getString("id"), x.getString("name"), x.optString("guardian").ifBlank { null })
                        }
                    },
                )
            }
        }
        val d = o.getJSONObject("destination")
        return Plan(
            title = o.getString("title"), vehicle = o.getString("vehicle"), driverName = o.getString("driverName"),
            stops = stops, destination = Destination(d.getString("name"), d.getDouble("lat"), d.getDouble("lon"), d.optString("address")),
            useSeatSignals = o.optBoolean("useSeatSignals", true), trunkCounts = o.optBoolean("trunkCounts", false),
        )
    }

    fun toJson(p: Plan): JSONObject = JSONObject()
        .put("title", p.title).put("vehicle", p.vehicle).put("driverName", p.driverName)
        .put("destination", JSONObject().put("name", p.destination.name))
        .put("stops", JSONArray(p.stops.map { s ->
            JSONObject().put("id", s.id).put("place", s.place).put("meetHint", s.meetHint).put("type", s.type.name)
                .put("riders", JSONArray(s.riders.map { r ->
                    JSONObject().put("id", r.id).put("name", r.name).put("guardian", r.guardian ?: "")
                }))
        }))
}
