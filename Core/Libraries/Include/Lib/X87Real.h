/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature ZH Commander 11/10/2026 X87Real: the value retail Zero Hour 1.04's trig
// functions hand back, for the retail-rules engine (SAGE_RETAIL_RULES).
//
// Retail is VC6 code running the x87 FPU at 24-bit precision (setFPMode). Precision control rounds
// add, subtract, multiply, divide and square root to float, but not fsin, fcos, fptan or fpatan,
// and VC6 returns a float function's result in the FPU register without rounding it: retail's Sin
// is literally "fld x; fsin; ret". So "pos.x + Cos(a) * 1000.0f" multiplies the unrounded cosine
// and rounds once per operation (disassembly: call Cos; fmul; fadd; fstp). Rounding the cosine to
// a float first, as a float return type does, put a walking infantry unit's goal a little off and
// a retail replay out of sync.
//
// X87Real carries the unrounded result (a double stands in for the 80-bit register) and rounds to
// float exactly where x87 would: once per arithmetic operation, and when stored into a Real. Only
// the trig functions return it, so every call site gets retail's arithmetic without being edited.

#pragma once

#include <type_traits>

struct X87Real
{
	double v;

	explicit X87Real(double value) : v(value) {}

	// Stored into a float variable or passed as a float argument: fstp rounds it.
	operator float() const { return (float)v; }

	X87Real operator-() const { return X87Real(-v); }
	X87Real operator+() const { return *this; }
};

template<typename T, typename R>
using X87IfArith = typename std::enable_if<std::is_arithmetic<T>::value, R>::type;

template<typename T>
using X87IfFloating = typename std::enable_if<std::is_floating_point<T>::value, T&>::type;

// One x87 operation on the unrounded value, rounded to float by precision control.
template<typename T> inline X87IfArith<T, float> operator+(X87Real a, T b) { return (float)(a.v + (double)b); }
template<typename T> inline X87IfArith<T, float> operator+(T a, X87Real b) { return (float)((double)a + b.v); }
inline float operator+(X87Real a, X87Real b) { return (float)(a.v + b.v); }

template<typename T> inline X87IfArith<T, float> operator-(X87Real a, T b) { return (float)(a.v - (double)b); }
template<typename T> inline X87IfArith<T, float> operator-(T a, X87Real b) { return (float)((double)a - b.v); }
inline float operator-(X87Real a, X87Real b) { return (float)(a.v - b.v); }

template<typename T> inline X87IfArith<T, float> operator*(X87Real a, T b) { return (float)(a.v * (double)b); }
template<typename T> inline X87IfArith<T, float> operator*(T a, X87Real b) { return (float)((double)a * b.v); }
inline float operator*(X87Real a, X87Real b) { return (float)(a.v * b.v); }

template<typename T> inline X87IfArith<T, float> operator/(X87Real a, T b) { return (float)(a.v / (double)b); }
template<typename T> inline X87IfArith<T, float> operator/(T a, X87Real b) { return (float)((double)a / b.v); }
inline float operator/(X87Real a, X87Real b) { return (float)(a.v / b.v); }

// fcomp compares the unrounded value with the operand as it is.
template<typename T> inline X87IfArith<T, bool> operator<(X87Real a, T b) { return a.v < (double)b; }
template<typename T> inline X87IfArith<T, bool> operator<(T a, X87Real b) { return (double)a < b.v; }
inline bool operator<(X87Real a, X87Real b) { return a.v < b.v; }

template<typename T> inline X87IfArith<T, bool> operator>(X87Real a, T b) { return a.v > (double)b; }
template<typename T> inline X87IfArith<T, bool> operator>(T a, X87Real b) { return (double)a > b.v; }
inline bool operator>(X87Real a, X87Real b) { return a.v > b.v; }

template<typename T> inline X87IfArith<T, bool> operator<=(X87Real a, T b) { return a.v <= (double)b; }
template<typename T> inline X87IfArith<T, bool> operator<=(T a, X87Real b) { return (double)a <= b.v; }
inline bool operator<=(X87Real a, X87Real b) { return a.v <= b.v; }

template<typename T> inline X87IfArith<T, bool> operator>=(X87Real a, T b) { return a.v >= (double)b; }
template<typename T> inline X87IfArith<T, bool> operator>=(T a, X87Real b) { return (double)a >= b.v; }
inline bool operator>=(X87Real a, X87Real b) { return a.v >= b.v; }

template<typename T> inline X87IfArith<T, bool> operator==(X87Real a, T b) { return a.v == (double)b; }
template<typename T> inline X87IfArith<T, bool> operator==(T a, X87Real b) { return (double)a == b.v; }
inline bool operator==(X87Real a, X87Real b) { return a.v == b.v; }

template<typename T> inline X87IfArith<T, bool> operator!=(X87Real a, T b) { return a.v != (double)b; }
template<typename T> inline X87IfArith<T, bool> operator!=(T a, X87Real b) { return (double)a != b.v; }
inline bool operator!=(X87Real a, X87Real b) { return a.v != b.v; }

// "x += Cos(a)": the unrounded value is added, then the sum is stored.
template<typename T> inline X87IfFloating<T> operator+=(T &a, X87Real b) { a = (T)((double)a + b.v); return a; }
template<typename T> inline X87IfFloating<T> operator-=(T &a, X87Real b) { a = (T)((double)a - b.v); return a; }
template<typename T> inline X87IfFloating<T> operator*=(T &a, X87Real b) { a = (T)((double)a * b.v); return a; }
template<typename T> inline X87IfFloating<T> operator/=(T &a, X87Real b) { a = (T)((double)a / b.v); return a; }
