'use strict';

function formatTotals(totals) {
  return 'total=' + totals.total.toFixed(2);
}

function render(order) {
  return formatTotals(order.totals);
}

module.exports = { formatTotals, render };
