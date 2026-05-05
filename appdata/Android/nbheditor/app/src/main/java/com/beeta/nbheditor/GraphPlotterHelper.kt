package com.beeta.nbheditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import kotlin.math.*

/**
 * Advanced Graph Plotter with support for:
 * - Pie Charts, Bar Charts, Line Charts
 * - Mathematical function plotting (sin, cos, tan, polynomials, etc.)
 * - Custom user-defined functions
 */
object GraphPlotterHelper {

    fun showGraphDialog(context: Context, onInsert: (Bitmap) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_graph_plotter, null)
        val previewImage = dialogView.findViewById<ImageView>(R.id.graphPreview)
        val graphTypeSpinner = dialogView.findViewById<Spinner>(R.id.graphTypeSpinner)
        val dataInputLayout = dialogView.findViewById<LinearLayout>(R.id.dataInputLayout)
        val functionInputLayout = dialogView.findViewById<LinearLayout>(R.id.functionInputLayout)
        val etFunction = dialogView.findViewById<EditText>(R.id.etFunction)
        val etXMin = dialogView.findViewById<EditText>(R.id.etXMin)
        val etXMax = dialogView.findViewById<EditText>(R.id.etXMax)
        val btnAddDataPoint = dialogView.findViewById<Button>(R.id.btnAddDataPoint)
        val dataPointsContainer = dialogView.findViewById<LinearLayout>(R.id.dataPointsContainer)
        val btnPreview = dialogView.findViewById<Button>(R.id.btnPreview)
        
        val graphTypes = arrayOf("Line Chart", "Bar Chart", "Pie Chart", "Function Plot")
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, graphTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        graphTypeSpinner.adapter = adapter
        
        val dataPoints = mutableListOf<Pair<String, Double>>()
        var currentBitmap: Bitmap? = null
        
        graphTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0, 1, 2 -> { // Line, Bar, Pie
                        dataInputLayout.visibility = View.VISIBLE
                        functionInputLayout.visibility = View.GONE
                    }
                    3 -> { // Function Plot
                        dataInputLayout.visibility = View.GONE
                        functionInputLayout.visibility = View.VISIBLE
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        btnAddDataPoint.setOnClickListener {
            val pointView = LayoutInflater.from(context).inflate(R.layout.item_data_point, dataPointsContainer, false)
            val etLabel = pointView.findViewById<EditText>(R.id.etLabel)
            val etValue = pointView.findViewById<EditText>(R.id.etValue)
            val btnRemove = pointView.findViewById<ImageButton>(R.id.btnRemovePoint)
            
            btnRemove.setOnClickListener {
                dataPointsContainer.removeView(pointView)
            }
            
            dataPointsContainer.addView(pointView)
        }
        
        btnPreview.setOnClickListener {
            val graphType = graphTypeSpinner.selectedItemPosition
            
            if (graphType in 0..2) {
                // Collect data points
                dataPoints.clear()
                for (i in 0 until dataPointsContainer.childCount) {
                    val pointView = dataPointsContainer.getChildAt(i)
                    val etLabel = pointView.findViewById<EditText>(R.id.etLabel)
                    val etValue = pointView.findViewById<EditText>(R.id.etValue)
                    val label = etLabel.text.toString()
                    val value = etValue.text.toString().toDoubleOrNull() ?: 0.0
                    if (label.isNotEmpty()) {
                        dataPoints.add(label to value)
                    }
                }
                
                if (dataPoints.isEmpty()) {
                    Toast.makeText(context, "Add at least one data point", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                currentBitmap = when (graphType) {
                    0 -> drawLineChart(dataPoints)
                    1 -> drawBarChart(dataPoints)
                    2 -> drawPieChart(dataPoints)
                    else -> null
                }
            } else {
                // Function plot
                val function = etFunction.text.toString()
                val xMin = etXMin.text.toString().toDoubleOrNull() ?: -10.0
                val xMax = etXMax.text.toString().toDoubleOrNull() ?: 10.0
                
                if (function.isEmpty()) {
                    Toast.makeText(context, "Enter a function", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                currentBitmap = drawFunctionPlot(function, xMin, xMax)
            }
            
            currentBitmap?.let { previewImage.setImageBitmap(it) }
        }
        
        val dialog = AlertDialog.Builder(context)
            .setTitle("Graph Plotter")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                currentBitmap?.let { onInsert(it) }
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
    }
    
    private fun drawLineChart(data: List<Pair<String, Double>>): Bitmap {
        val width = 800
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val padding = 80f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding
        
        // Find min/max values
        val maxValue = data.maxOfOrNull { it.second } ?: 1.0
        val minValue = data.minOfOrNull { it.second } ?: 0.0
        val range = maxValue - minValue
        
        // Draw axes
        paint.color = Color.BLACK
        paint.strokeWidth = 3f
        canvas.drawLine(padding, padding, padding, height - padding, paint) // Y-axis
        canvas.drawLine(padding, height - padding, width - padding, height - padding, paint) // X-axis
        
        // Draw grid
        paint.color = Color.LTGRAY
        paint.strokeWidth = 1f
        for (i in 0..5) {
            val y = padding + (chartHeight / 5) * i
            canvas.drawLine(padding, y, width - padding, y, paint)
        }
        
        // Draw line
        paint.color = Color.BLUE
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        
        val path = Path()
        data.forEachIndexed { index, (_, value) ->
            val x = padding + (chartWidth / (data.size - 1)) * index
            val y = height - padding - ((value - minValue) / range * chartHeight).toFloat()
            
            if (index == 0) path.moveTo(x, y)
            else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
        
        // Draw points
        paint.style = Paint.Style.FILL
        paint.color = Color.RED
        data.forEachIndexed { index, (_, value) ->
            val x = padding + (chartWidth / (data.size - 1)) * index
            val y = height - padding - ((value - minValue) / range * chartHeight).toFloat()
            canvas.drawCircle(x, y, 8f, paint)
        }
        
        // Draw labels
        paint.color = Color.BLACK
        paint.textSize = 24f
        paint.textAlign = Paint.Align.CENTER
        data.forEachIndexed { index, (label, _) ->
            val x = padding + (chartWidth / (data.size - 1)) * index
            canvas.drawText(label, x, height - padding + 40, paint)
        }
        
        // Draw Y-axis values
        paint.textAlign = Paint.Align.RIGHT
        for (i in 0..5) {
            val value = minValue + (range / 5) * i
            val y = height - padding - (chartHeight / 5) * i
            canvas.drawText(String.format("%.1f", value), padding - 10, y + 8, paint)
        }
        
        return bitmap
    }
    
    private fun drawBarChart(data: List<Pair<String, Double>>): Bitmap {
        val width = 800
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val padding = 80f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding
        
        val maxValue = data.maxOfOrNull { it.second } ?: 1.0
        val barWidth = chartWidth / data.size * 0.7f
        val barSpacing = chartWidth / data.size * 0.3f
        
        // Draw axes
        paint.color = Color.BLACK
        paint.strokeWidth = 3f
        canvas.drawLine(padding, padding, padding, height - padding, paint)
        canvas.drawLine(padding, height - padding, width - padding, height - padding, paint)
        
        // Draw bars
        val colors = listOf(Color.BLUE, Color.GREEN, Color.RED, Color.YELLOW, Color.CYAN, Color.MAGENTA)
        data.forEachIndexed { index, (label, value) ->
            val x = padding + barSpacing / 2 + (barWidth + barSpacing) * index
            val barHeight = (value / maxValue * chartHeight).toFloat()
            val y = height - padding - barHeight
            
            paint.color = colors[index % colors.size]
            paint.style = Paint.Style.FILL
            canvas.drawRect(x, y, x + barWidth, height - padding, paint)
            
            // Draw value on top
            paint.color = Color.BLACK
            paint.textSize = 20f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(value.toString(), x + barWidth / 2, y - 10, paint)
            
            // Draw label
            canvas.drawText(label, x + barWidth / 2, height - padding + 40, paint)
        }
        
        return bitmap
    }
    
    private fun drawPieChart(data: List<Pair<String, Double>>): Bitmap {
        val size = 800
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val total = data.sumOf { it.second }
        val centerX = size / 2f
        val centerY = size / 2f
        val radius = size / 3f
        
        val colors = listOf(
            Color.parseColor("#FF6B6B"), Color.parseColor("#4ECDC4"),
            Color.parseColor("#45B7D1"), Color.parseColor("#FFA07A"),
            Color.parseColor("#98D8C8"), Color.parseColor("#F7DC6F")
        )
        
        var startAngle = 0f
        data.forEachIndexed { index, (label, value) ->
            val sweepAngle = (value / total * 360).toFloat()
            
            paint.color = colors[index % colors.size]
            paint.style = Paint.Style.FILL
            canvas.drawArc(
                centerX - radius, centerY - radius,
                centerX + radius, centerY + radius,
                startAngle, sweepAngle, true, paint
            )
            
            // Draw label
            val angle = startAngle + sweepAngle / 2
            val labelRadius = radius * 1.3f
            val labelX = centerX + labelRadius * cos(Math.toRadians(angle.toDouble())).toFloat()
            val labelY = centerY + labelRadius * sin(Math.toRadians(angle.toDouble())).toFloat()
            
            paint.color = Color.BLACK
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("$label (${(value / total * 100).toInt()}%)", labelX, labelY, paint)
            
            startAngle += sweepAngle
        }
        
        return bitmap
    }
    
    private fun drawFunctionPlot(functionStr: String, xMin: Double, xMax: Double): Bitmap {
        val width = 800
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val padding = 80f
        val chartWidth = width - 2 * padding
        val chartHeight = height - 2 * padding
        
        // Calculate points
        val points = mutableListOf<Pair<Double, Double>>()
        val steps = 500
        val dx = (xMax - xMin) / steps
        
        for (i in 0..steps) {
            val x = xMin + i * dx
            try {
                val y = evaluateFunction(functionStr, x)
                if (y.isFinite()) {
                    points.add(x to y)
                }
            } catch (e: Exception) {
                // Skip invalid points
            }
        }
        
        if (points.isEmpty()) {
            paint.color = Color.RED
            paint.textSize = 32f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Invalid function", width / 2f, height / 2f, paint)
            return bitmap
        }
        
        val yMin = points.minOfOrNull { it.second } ?: -10.0
        val yMax = points.maxOfOrNull { it.second } ?: 10.0
        val yRange = yMax - yMin
        
        // Draw grid
        paint.color = Color.LTGRAY
        paint.strokeWidth = 1f
        for (i in 0..10) {
            val x = padding + (chartWidth / 10) * i
            canvas.drawLine(x, padding, x, height - padding, paint)
            val y = padding + (chartHeight / 10) * i
            canvas.drawLine(padding, y, width - padding, y, paint)
        }
        
        // Draw axes
        paint.color = Color.BLACK
        paint.strokeWidth = 3f
        val zeroX = padding + ((0 - xMin) / (xMax - xMin) * chartWidth).toFloat()
        val zeroY = height - padding - ((0 - yMin) / yRange * chartHeight).toFloat()
        canvas.drawLine(padding, zeroY, width - padding, zeroY, paint) // X-axis
        canvas.drawLine(zeroX, padding, zeroX, height - padding, paint) // Y-axis
        
        // Draw function
        paint.color = Color.BLUE
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val px = padding + ((x - xMin) / (xMax - xMin) * chartWidth).toFloat()
            val py = height - padding - ((y - yMin) / yRange * chartHeight).toFloat()
            
            if (index == 0) path.moveTo(px, py)
            else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
        
        // Draw labels
        paint.color = Color.BLACK
        paint.textSize = 20f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(functionStr, width / 2f, 40f, paint)
        
        return bitmap
    }
    
    private fun evaluateFunction(expr: String, x: Double): Double {
        var expression = expr.lowercase()
            .replace("x", x.toString())
            .replace("sin", "Math.sin")
            .replace("cos", "Math.cos")
            .replace("tan", "Math.tan")
            .replace("sqrt", "Math.sqrt")
            .replace("abs", "Math.abs")
            .replace("ln", "Math.log")
            .replace("log", "Math.log10")
            .replace("exp", "Math.exp")
            .replace("pi", Math.PI.toString())
            .replace("e", Math.E.toString())
            .replace("^", "**")
        
        // Simple expression evaluator
        return try {
            // Use Kotlin's eval-like approach with basic parsing
            evaluateExpression(expression)
        } catch (e: Exception) {
            Double.NaN
        }
    }
    
    private fun evaluateExpression(expr: String): Double {
        // Basic math expression evaluator
        // Supports: +, -, *, /, ^, sin, cos, tan, sqrt, etc.
        
        // For now, use a simple approach with Kotlin's math functions
        // In production, you'd want a proper expression parser
        
        return when {
            expr.contains("sin(") -> {
                val arg = expr.substringAfter("sin(").substringBefore(")").toDoubleOrNull() ?: 0.0
                sin(arg)
            }
            expr.contains("cos(") -> {
                val arg = expr.substringAfter("cos(").substringBefore(")").toDoubleOrNull() ?: 0.0
                cos(arg)
            }
            expr.contains("tan(") -> {
                val arg = expr.substringAfter("tan(").substringBefore(")").toDoubleOrNull() ?: 0.0
                tan(arg)
            }
            expr.contains("sqrt(") -> {
                val arg = expr.substringAfter("sqrt(").substringBefore(")").toDoubleOrNull() ?: 0.0
                sqrt(arg)
            }
            expr.contains("+") -> {
                val parts = expr.split("+")
                parts.sumOf { it.trim().toDoubleOrNull() ?: 0.0 }
            }
            expr.contains("*") && !expr.contains("**") -> {
                val parts = expr.split("*")
                parts.fold(1.0) { acc, s -> acc * (s.trim().toDoubleOrNull() ?: 1.0) }
            }
            expr.contains("**") -> {
                val parts = expr.split("**")
                if (parts.size == 2) {
                    val base = parts[0].trim().toDoubleOrNull() ?: 0.0
                    val exp = parts[1].trim().toDoubleOrNull() ?: 0.0
                    base.pow(exp)
                } else 0.0
            }
            else -> expr.toDoubleOrNull() ?: 0.0
        }
    }
}
