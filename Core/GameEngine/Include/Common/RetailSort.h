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

// GeneralsX @feature ZH Commander 12/10/2026 RetailSort: std::sort as retail Zero Hour 1.04 ran it.
//
// Retail is VC6 linked against STLport 4.5.3, whose std::sort is an introsort: median-of-three
// partitions down to 16 elements, then one insertion sort, with a heap sort if the recursion goes
// too deep. It is not stable, so where the simulation sorts elements that compare equal (units of
// the same size, for instance) the resulting order is that algorithm's, and the order decides who
// boards which transport. libc++'s std::sort is a different algorithm and orders equal elements
// differently. This is STLport's code (stl/_algo.c, stl/_heap.c, stl/_heap.h), same operations in
// the same order, for the retail-rules engine (SAGE_RETAIL_RULES).

#pragma once

#include <algorithm>
#include <iterator>

namespace RetailSort
{
	enum { kThreshold = 16 }; // STLport's __stl_threshold

	template<class It, class Comp>
	const typename std::iterator_traits<It>::value_type &median(const typename std::iterator_traits<It>::value_type &a,
		const typename std::iterator_traits<It>::value_type &b, const typename std::iterator_traits<It>::value_type &c, Comp comp)
	{
		if (comp(a, b))
		{
			if (comp(b, c))
				return b;
			else if (comp(a, c))
				return c;
			else
				return a;
		}
		else if (comp(a, c))
			return a;
		else if (comp(b, c))
			return c;
		else
			return b;
	}

	template<class It, class T, class Comp>
	It unguardedPartition(It first, It last, T pivot, Comp comp)
	{
		while (true)
		{
			while (comp(*first, pivot))
				++first;
			--last;
			while (comp(pivot, *last))
				--last;
			if (!(first < last))
				return first;
			std::iter_swap(first, last);
			++first;
		}
	}

	template<class It, class T, class Comp>
	void unguardedLinearInsert(It last, T val, Comp comp)
	{
		It next = last;
		--next;
		while (comp(val, *next))
		{
			*last = *next;
			last = next;
			--next;
		}
		*last = val;
	}

	template<class It, class T, class Comp>
	void linearInsert(It first, It last, T val, Comp comp)
	{
		if (comp(val, *first))
		{
			std::copy_backward(first, last, last + 1);
			*first = val;
		}
		else
			unguardedLinearInsert(last, val, comp);
	}

	template<class It, class Comp>
	void insertionSort(It first, It last, Comp comp)
	{
		if (first == last)
			return;
		for (It i = first + 1; i != last; ++i)
			linearInsert(first, i, typename std::iterator_traits<It>::value_type(*i), comp);
	}

	template<class It, class Comp>
	void unguardedInsertionSort(It first, It last, Comp comp)
	{
		for (It i = first; i != last; ++i)
			unguardedLinearInsert(i, typename std::iterator_traits<It>::value_type(*i), comp);
	}

	template<class It, class Comp>
	void finalInsertionSort(It first, It last, Comp comp)
	{
		if (last - first > kThreshold)
		{
			insertionSort(first, first + kThreshold, comp);
			unguardedInsertionSort(first + kThreshold, last, comp);
		}
		else
			insertionSort(first, last, comp);
	}

	template<class It, class Dist, class T, class Comp>
	void pushHeap(It first, Dist holeIndex, Dist topIndex, T val, Comp comp)
	{
		Dist parent = (holeIndex - 1) / 2;
		while (holeIndex > topIndex && comp(*(first + parent), val))
		{
			*(first + holeIndex) = *(first + parent);
			holeIndex = parent;
			parent = (holeIndex - 1) / 2;
		}
		*(first + holeIndex) = val;
	}

	template<class It, class Dist, class T, class Comp>
	void adjustHeap(It first, Dist holeIndex, Dist len, T val, Comp comp)
	{
		Dist topIndex = holeIndex;
		Dist secondChild = 2 * holeIndex + 2;
		while (secondChild < len)
		{
			if (comp(*(first + secondChild), *(first + (secondChild - 1))))
				secondChild--;
			*(first + holeIndex) = *(first + secondChild);
			holeIndex = secondChild;
			secondChild = 2 * (secondChild + 1);
		}
		if (secondChild == len)
		{
			*(first + holeIndex) = *(first + (secondChild - 1));
			holeIndex = secondChild - 1;
		}
		pushHeap(first, holeIndex, topIndex, val, comp);
	}

	template<class It, class T, class Comp>
	void popHeap(It first, It last, It result, T val, Comp comp)
	{
		typedef typename std::iterator_traits<It>::difference_type Dist;
		*result = *first;
		adjustHeap(first, Dist(0), Dist(last - first), val, comp);
	}

	template<class It, class Comp>
	void makeHeap(It first, It last, Comp comp)
	{
		typedef typename std::iterator_traits<It>::difference_type Dist;
		typedef typename std::iterator_traits<It>::value_type T;
		if (last - first < 2)
			return;
		Dist len = last - first;
		Dist parent = (len - 2) / 2;
		while (true)
		{
			adjustHeap(first, parent, len, T(*(first + parent)), comp);
			if (parent == 0)
				return;
			parent--;
		}
	}

	template<class It, class Comp>
	void sortHeap(It first, It last, Comp comp)
	{
		typedef typename std::iterator_traits<It>::value_type T;
		while (last - first > 1)
		{
			--last;
			popHeap(first, last, last, T(*last), comp);
		}
	}

	template<class It, class Comp>
	void partialSort(It first, It middle, It last, Comp comp)
	{
		typedef typename std::iterator_traits<It>::value_type T;
		makeHeap(first, middle, comp);
		for (It i = middle; i < last; ++i)
			if (comp(*i, *first))
				popHeap(first, middle, i, T(*i), comp);
		sortHeap(first, middle, comp);
	}

	template<class It, class Size, class Comp>
	void introsortLoop(It first, It last, Size depthLimit, Comp comp)
	{
		typedef typename std::iterator_traits<It>::value_type T;
		while (last - first > kThreshold)
		{
			if (depthLimit == 0)
			{
				partialSort(first, last, last, comp);
				return;
			}
			--depthLimit;
			It cut = unguardedPartition(first, last,
				T(median<It>(*first, *(first + (last - first) / 2), *(last - 1), comp)), comp);
			introsortLoop(cut, last, depthLimit, comp);
			last = cut;
		}
	}

	template<class Size>
	Size lg(Size n)
	{
		Size k;
		for (k = 0; n != 1; n >>= 1)
			++k;
		return k;
	}

	template<class It, class Comp>
	void sort(It first, It last, Comp comp)
	{
		if (first != last)
		{
			introsortLoop(first, last, lg(last - first) * 2, comp);
			finalInsertionSort(first, last, comp);
		}
	}
}
