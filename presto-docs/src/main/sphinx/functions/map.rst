===========================
Map Functions and Operators
===========================

Subscript Operator: []
----------------------

The ``[]`` operator is used to retrieve the value corresponding to a given key from a map::

    SELECT name_to_age_map['Bob'] AS bob_age;

Map Functions
-------------

.. function:: cardinality(x) -> bigint
    :noindex:

    Returns the cardinality (size) of the map ``x``.

.. function:: element_at(map<K,V>, key) -> V
    :noindex:

    Returns value for given ``key``, or ``NULL`` if the key is not contained in the map.

.. function:: map() -> map<unknown, unknown>

    Returns an empty map. ::

        SELECT map(); -- {}

.. function:: map(array<K>, array<V>) -> map<K,V>

    Returns a map created using the given key/value arrays. ::

        SELECT map(ARRAY[1,3], ARRAY[2,4]); -- {1 -> 2, 3 -> 4}

    See also :func:`map_agg` and :func:`multimap_agg` for creating a map as an aggregation.

.. function:: map_from_entries(array<row<K,V>>) -> map<K,V>

    Returns a map created from the given array of entries. ::

        SELECT map_from_entries(ARRAY[(1, 'x'), (2, 'y')]); -- {1 -> 'x', 2 -> 'y'}

.. function:: map_entries(map<K,V>) -> array<row<K,V>>

    Returns an array of all entries in the given map. ::

        SELECT map_entries(MAP(ARRAY[1, 2], ARRAY['x', 'y'])); -- [ROW(1, 'x'), ROW(2, 'y')]

.. function:: map_concat(map1<K,V>, map2<K,V>, ..., mapN<K,V>) -> map<K,V>

   Returns the union of all the given maps. If a key is found in multiple given maps,
   that key's value in the resulting map comes from the last one of those maps.

.. function:: map_filter(map<K,V>, function) -> map<K,V>
    :noindex:

    See :func:`map_filter`.

.. function:: transform_keys(map<K1,V>, function) -> MAP<K2,V>
    :noindex:

    See :func:`transform_keys`.

.. function:: transform_values(map<K,V1>, function) -> MAP<K,V2>
    :noindex:

    See :func:`transform_values`.

.. function:: map_keys(x<K,V>) -> array<K>

    Returns all the keys in the map ``x``.

.. function:: map_values(x<K,V>) -> array<V>

    Returns all the values in the map ``x``.
