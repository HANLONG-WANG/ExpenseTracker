package com.yohaku.expensetracker.ui.features

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*

/** OpenFreeMap Liberty carries shop POIs; each theme repaints it into its own paper. */
internal const val PLACE_MAP_STYLE="https://tiles.openfreemap.org/styles/liberty"

private class MapPaper(val bg:String,val landuse:String,val park:String,val wood:String,val water:String,val waterway:String,
    val building:String,val buildingLine:String,val road:String,val major:String,val casing:String,val majorCasing:String,
    val rail:String,val boundary:String,val label:String,val muted:String,val halo:String,val poi:String,val iconOpacity:Float)

private fun paper(themeId:String)=when(themeId) {
    // Printed city plate: near-monochrome greens, hairline casings, icons pushed back.
    "journal"->MapPaper("#f1e8d5","#ece2cc","#e0e2c6","#d9dcbc","#cfdad8","#b4c6c9","#e8dcc3","#d4cbb5","#faf5e7","#fbf1d8","#d4cbb5","#c4b393",
        "#bdb29a","#898679","#354d61","#898679","#f1e8d5","#3b5b70",.7f)
    "objects"->MapPaper("#faf1e5","#f6ead8","#dcebcf","#cfe4c1","#c9e2ec","#b2d5e3","#f3dcc6","#e9cfb3","#fffcf5","#ffe9c2","#e9d8be","#e2bf90",
        "#d8c4ad","#9c8976","#634b3b","#9c8976","#fffcf5","#ae6246",1f)
    else->MapPaper("#f7f7ef","#f0f0e6","#e3e9da","#dce4d2","#d6ded8","#c2cec6","#ebebe1","#d2dacc","#fffef8","#fffef8","#d6dccf","#b4beb0",
        "#c8cfc3","#9aa595","#263d32","#788577","#f7f7ef","#355b43",.5f)
}

private val majorRoad=Regex("motorway|trunk|primary|secondary|link")
private val localName=Expression.coalesce(Expression.get("name"),Expression.get("name:latin"))

internal fun Style.applyPlaceTheme(themeId:String) {
    val p=paper(themeId)
    layers.forEach{layer->
        val id=layer.id
        when(layer) {
            is BackgroundLayer->layer.setProperties(PropertyFactory.backgroundColor(p.bg))
            is RasterLayer->layer.setProperties(PropertyFactory.rasterOpacity(.25f),PropertyFactory.rasterSaturation(-1f))
            is FillExtrusionLayer->layer.setProperties(PropertyFactory.visibility(Property.NONE))
            is FillLayer->when {
                id=="water"->layer.setProperties(PropertyFactory.fillColor(p.water))
                id=="building"->layer.setProperties(PropertyFactory.fillColor(p.building),PropertyFactory.fillOutlineColor(p.buildingLine))
                id=="landcover_wood"->layer.setProperties(PropertyFactory.fillColor(p.wood))
                id=="park"||id in setOf("landcover_grass","landcover_wetland","landuse_pitch","landuse_track","landuse_cemetery")->layer.setProperties(PropertyFactory.fillColor(p.park),PropertyFactory.fillOutlineColor(p.park))
                id=="road_area_pattern"->Unit
                else->layer.setProperties(PropertyFactory.fillColor(p.landuse))
            }
            is LineLayer->layer.setProperties(PropertyFactory.lineColor(when {
                id.startsWith("waterway")->p.waterway
                id=="park_outline"->p.park
                id.startsWith("boundary")->p.boundary
                "rail" in id->p.rail
                "casing" in id->if(majorRoad.containsMatchIn(id))p.majorCasing else p.casing
                majorRoad.containsMatchIn(id)->p.major
                else->p.road
            }))
            is SymbolLayer->{
                if("shield" in id){layer.setProperties(PropertyFactory.visibility(Property.NONE));return@forEach}
                val named=id.startsWith("poi")||id.startsWith("label_")||id.startsWith("water_name")||id=="airport"||id.startsWith("highway-name")||id=="waterway_line_label"
                if(named)layer.setProperties(PropertyFactory.textField(localName),PropertyFactory.textHaloColor(p.halo))
                when {
                    id.startsWith("poi")->layer.setProperties(PropertyFactory.textColor(p.poi),PropertyFactory.iconOpacity(p.iconOpacity))
                    id.startsWith("label_")->layer.setProperties(PropertyFactory.textColor(p.label))
                    named->layer.setProperties(PropertyFactory.textColor(p.muted))
                }
            }
        }
    }
}
